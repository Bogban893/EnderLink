package org.enderlink.desktop.presentation

import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(15)
class DesktopJobsTest {
    @Test
    fun `background job excludes concurrent actions and delivers completion on EDT`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CompletableFuture<Boolean>()
        val secondActionRan = AtomicBoolean(false)
        val callbacksOnEdt = mutableListOf<Boolean>()
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        val messages = mutableListOf<String>()
        lateinit var jobs: DesktopJobs
        SwingUtilities.invokeAndWait {
            jobs = DesktopJobs(
                setBusy = { busy, cancellable ->
                    callbacksOnEdt += SwingUtilities.isEventDispatchThread()
                    states += busy to cancellable
                },
                status = messages::add,
                beforeStart = { callbacksOnEdt += SwingUtilities.isEventDispatchThread() },
                afterFinish = { callbacksOnEdt += SwingUtilities.isEventDispatchThread() },
                onFailure = { error, _ -> completed.completeExceptionally(error) },
            )
            jobs.run("first", true, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                SwingUtilities.isEventDispatchThread()
            }) { workerOnEdt ->
                callbacksOnEdt += SwingUtilities.isEventDispatchThread()
                completed.complete(workerOnEdt)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(jobs.isBusy)
            SwingUtilities.invokeAndWait {
                jobs.run("second", { secondActionRan.set(true) }) {}
            }
        } finally {
            release.countDown()
        }
        assertFalse(completed.get(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            assertFalse(jobs.isBusy)
            assertFalse(secondActionRan.get())
            assertEquals(listOf(true to true, false to false), states)
            assertTrue(callbacksOnEdt.all { it })
            assertEquals(listOf("first", "Дождитесь завершения текущей операции"), messages)
        }
    }

    @Test
    fun `failure restores controls before notification and allows a follow-up job`() {
        val failure = IOException("disk unavailable")
        val completed = CompletableFuture<Throwable>()
        val successCalled = AtomicBoolean(false)
        val events = mutableListOf<String>()
        lateinit var jobs: DesktopJobs
        SwingUtilities.invokeAndWait {
            jobs = DesktopJobs(
                setBusy = { busy, _ -> events += if (busy) "busy" else "idle" },
                status = {},
                beforeStart = {},
                afterFinish = { events += "finished" },
                onFailure = { error, cancellable ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertTrue(cancellable)
                    assertFalse(jobs.isBusy)
                    events += "failure"
                    jobs.run("follow-up", { error }) { completed.complete(it) }
                },
            )
            jobs.run("failing", true, { throw failure }) { successCalled.set(true) }
        }
        assertSame(failure, completed.get(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            assertFalse(successCalled.get())
            assertFalse(jobs.isBusy)
            assertEquals(listOf("busy", "idle", "finished", "failure", "busy", "idle", "finished"), events)
        }
    }

    @Test
    fun `interruption is reported as failure rather than completion`() {
        val interrupted = InterruptedException("cancelled")
        val completed = CompletableFuture<Throwable>()
        val successCalled = AtomicBoolean(false)
        SwingUtilities.invokeAndWait {
            val jobs = DesktopJobs({ _, _ -> }, {}, {}, {}, { error, _ -> completed.complete(error) })
            jobs.run("cancelled", true, { throw interrupted }) { successCalled.set(true) }
        }
        assertSame(interrupted, completed.get(5, TimeUnit.SECONDS))
        assertFalse(successCalled.get())
    }
}
