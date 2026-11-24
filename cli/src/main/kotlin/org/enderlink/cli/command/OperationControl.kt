package org.enderlink.cli.command

import java.io.IOException

internal class OperationCancelledException : IOException("Операция отменена")

/** Cancellation closes transports; local publication and activation finish without interruption. */
class OperationControl {
    private val lock = Any()
    private var cancelled = false
    private var protected = false
    private var waiting: Thread? = null
    private val resources = mutableMapOf<AutoCloseable, () -> Unit>()

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    /** Fast UI-safe request. Call [closeCancelledResources] off the UI thread afterwards. */
    fun requestCancel(): Boolean = synchronized(lock) {
        if (protected) false else {
            cancelled = true
            waiting?.interrupt()
            true
        }
    }

    fun closeCancelledResources() {
        val callbacks = synchronized(lock) {
            if (!cancelled) return
            resources.values.toList()
        }
        callbacks.forEach { runCatching(it) }
    }

    fun checkCancelled() {
        if (isCancelled) throw OperationCancelledException()
    }

    /** Once committing starts, cancelling would misrepresent an already activated version. */
    fun protectCommit() = synchronized(lock) {
        checkCancelled()
        protected = true
    }

    fun allowCancellation() = synchronized(lock) { protected = false }

    fun <T> resource(resource: AutoCloseable, abort: () -> Unit = { resource.close() }, action: () -> T): T {
        synchronized(lock) {
            checkCancelled()
            resources[resource] = abort
        }
        return try { action() } finally { synchronized(lock) { resources.remove(resource) } }
    }

    /** Only native connection setup uses interruption; disk writes never do. */
    fun <T> networkWait(action: () -> T): T {
        synchronized(lock) { checkCancelled(); waiting = Thread.currentThread() }
        return try { action() } finally {
            synchronized(lock) {
                waiting = null
                if (cancelled) Thread.interrupted()
            }
        }
    }
}
