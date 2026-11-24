package org.enderlink.desktop.presentation

import org.enderlink.desktop.ui.screens.ReceiveScreen
import org.enderlink.desktop.ui.screens.SendScreen
import org.enderlink.desktop.ui.size
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Coalesces progress updates on the Swing event thread. */
internal class TransferProgressPresenter(private val send: SendScreen, private val receive: ReceiveScreen) {
    var receiving = false
    private val lastProgressNs = AtomicLong(0)

    fun update(done: Long, total: Long) {
        val now = System.nanoTime()
        if (done < total && now - lastProgressNs.get() < 60_000_000L) return
        lastProgressNs.set(now)
        SwingUtilities.invokeLater {
            val value = if (total == 0L) 0 else ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
            val bar = if (receiving) receive.receiveProgress else send.sendProgress
            val text = if (receiving) receive.receiveProgressText else send.sendProgressText
            bar.value = value
            text.text = "${size(done)} из ${size(total)} · ${value / 10}%"
        }
    }

    fun reset() {
        lastProgressNs.set(0)
        send.sendProgress.value = 0; receive.receiveProgress.value = 0
        send.sendProgressText.text = "Подготовка…"; receive.receiveProgressText.text = "Подготовка…"
    }
}
