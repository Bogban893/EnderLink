package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.screens.ReceiveScreen
import org.enderlink.desktop.ui.screens.SendScreen
import org.enderlink.desktop.ui.size
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Coalesces progress updates on the Swing event thread. */
internal class TransferProgressPresenter(private val send: SendScreen, private val receive: ReceiveScreen) {
    var receiving = false
    private val lastProgressNs = AtomicLong(0)
    private var active = false

    fun update(done: Long, total: Long) {
        val now = System.nanoTime()
        if (done < total && now - lastProgressNs.get() < 60_000_000L) return
        lastProgressNs.set(now)
        SwingUtilities.invokeLater {
            if (!active) return@invokeLater
            val value = if (total == 0L) 0 else ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
            val bar = if (receiving) receive.receiveProgress else send.sendProgress
            val text = if (receiving) receive.receiveProgressText else send.sendProgressText
            bar.transferring(value)
            text.text = messages.text("transfer.progress", size(done), size(total), (value / 10).toString())
            text.foreground = C.text
        }
    }

    fun reset() {
        lastProgressNs.set(0)
        active = true
        send.sendProgress.transferring(0); receive.receiveProgress.transferring(0)
        (if (receiving) receive.receiveProgress else send.sendProgress).waiting()
        send.sendProgressText.text = messages.text("status.preparing"); receive.receiveProgressText.text = messages.text("status.preparing")
        send.sendProgressText.foreground = C.muted; receive.receiveProgressText.foreground = C.muted
    }

    fun complete() {
        if (!active) return
        active = false
        (if (receiving) receive.receiveProgress else send.sendProgress).succeeded()
        (if (receiving) receive.receiveProgressText else send.sendProgressText).foreground = C.green
    }

    fun fail(cancelled: Boolean = false) {
        if (!active) return
        active = false
        val bar = if (receiving) receive.receiveProgress else send.sendProgress
        val text = if (receiving) receive.receiveProgressText else send.sendProgressText
        if (cancelled) bar.transferring(bar.value) else bar.failed()
        text.text = messages.text(if (cancelled) "status.cancelled" else "status.errorShort")
        text.foreground = if (cancelled) C.gold else C.red
    }

    fun conflict() {
        if (!active) return
        active = false
        val bar = if (receiving) receive.receiveProgress else send.sendProgress
        val text = if (receiving) receive.receiveProgressText else send.sendProgressText
        bar.transferring(bar.value)
        bar.foreground = C.gold
        text.text = messages.text("conflict.saved")
        text.foreground = C.gold
    }
}
