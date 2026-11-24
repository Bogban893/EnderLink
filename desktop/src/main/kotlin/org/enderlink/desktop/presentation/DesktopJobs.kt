package org.enderlink.desktop.presentation

import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

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
    val isBusy: Boolean get() = busy.get()

    fun <T> run(title: String, action: () -> T, success: (T) -> Unit) = run(title, false, action, success)

    fun <T> run(title: String, cancellable: Boolean, action: () -> T, success: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { status("Дождитесь завершения текущей операции"); return }
        beforeStart(cancellable)
        setBusy(true, cancellable)
        status(title)
        Thread({
            val result = runCatching(action)
            SwingUtilities.invokeLater {
                busy.set(false)
                setBusy(false, false)
                afterFinish()
                result.onSuccess(success).onFailure { onFailure(it, cancellable) }
            }
        }, "enderlink-desktop-job").apply { isDaemon = true; start() }
    }
}
