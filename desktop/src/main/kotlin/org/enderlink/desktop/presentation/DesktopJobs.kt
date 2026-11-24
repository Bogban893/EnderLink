package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Start actions on the EDT. Runs one blocking action at a time on a worker thread,
 * delivering results and failures on the EDT after restoring the controls.
 */
internal class DesktopJobs(
    private val setBusy: (Boolean, Boolean) -> Unit,
    private val status: (String) -> Unit,
    private val beforeStart: (Boolean) -> Unit,
    private val afterFinish: () -> Unit,
    private val onFailure: (Throwable, Boolean) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    private var stageText = ""
    private var stageStarted = 0L
    private val elapsedTimer = Timer(1_000) {
        if (busy.get()) status(messages.text("status.elapsed", stageText, ((System.nanoTime() - stageStarted) / 1_000_000_000).toString()))
    }
    val isBusy: Boolean get() = busy.get()

    fun updateStatus(text: String) = SwingUtilities.invokeLater {
        stageText = text
        stageStarted = System.nanoTime()
        status(text)
    }

    fun <T> run(title: String, action: () -> T, success: (T) -> Unit) = run(title, false, action, success)

    fun <T> run(title: String, cancellable: Boolean, action: () -> T, success: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { status(messages.text("status.busy")); return }
        beforeStart(cancellable)
        setBusy(true, cancellable)
        stageText = title
        stageStarted = System.nanoTime()
        status(title)
        if (cancellable) elapsedTimer.start()
        Thread({
            val result = runCatching(action)
            SwingUtilities.invokeLater {
                elapsedTimer.stop()
                busy.set(false)
                setBusy(false, false)
                afterFinish()
                result.onSuccess(success).onFailure { onFailure(it, cancellable) }
            }
        }, "enderlink-desktop-job").apply { isDaemon = true; start() }
    }
}
