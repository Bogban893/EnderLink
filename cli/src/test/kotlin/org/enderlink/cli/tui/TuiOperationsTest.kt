package org.enderlink.cli.tui

import org.enderlink.cli.command.OperationControl
import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Timeout(30)
class TuiOperationsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `publication requires a closed world declaration before creating repository`() {
        val source = Files.createDirectory(root.resolve("Мой мир"))
        Files.writeString(source.resolve("level.dat"), "original")
        val path = root.resolve("repo")
        val session = TuiSession()
        session.execute("repository ${TuiWords.quote(path.toString())}")
        assertFalse(Files.exists(path))
        assertTrue(session.execute("world-versions").error)
        assertFalse(Files.exists(path))
        val command = "world-publish --source ${TuiWords.quote(source.toString())}"
        assertTrue(session.execute("world-receive --repository ${TuiWords.quote(source.toString())}").error)
        assertFalse(Files.exists(source.resolve(".enderlink")))
        assertTrue(session.execute("$command --closed --repository ${TuiWords.quote(source.resolve("nested-repo").toString())}").error)
        assertFalse(Files.exists(source.resolve("nested-repo")))
        assertTrue(session.execute("$command --closed --repository ${TuiWords.quote(source.toString())}").error)
        assertFalse(Files.exists(source.resolve(".enderlink")))
        assertTrue(session.execute(command).error)
        assertFalse(Files.exists(path))
        assertFalse(session.execute("$command --closed").error)
        val current = WorldRepository(path).current()!!
        assertEquals("original", Files.readString(source.resolve("level.dat")))
        assertFalse(Files.exists(source.resolve(".enderlink")))
        assertTrue(session.execute("world-versions").output.contains(current.versionId))
        assertEquals(listOf(current.versionId), session.state.versionIds)
        Files.writeString(source.resolve("level.dat"), "second")
        assertFalse(session.execute("$command --closed").error)
        assertEquals(2, WorldRepository(path).versions().size)
        assertFalse(session.execute("world-checkout --version ${current.versionId}").error)
        assertEquals("original", Files.readString(path.resolve("world/level.dat")))
        Files.writeString(path.resolve("world/level.dat"), "unpublished")
        val other = WorldRepository(path).versions().first { it.versionId != current.versionId }
        assertTrue(session.execute("world-checkout --version ${other.versionId}").error)
        assertEquals("unpublished", Files.readString(path.resolve("world/level.dat")))
    }

    @Test
    fun `TCP receive can be cancelled then restarted and completed with live output`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "world over TCP")
        val sender = TuiSession()
        val destination = root.resolve("receiver")
        val receiver = TuiSession()
        val from = root.resolve("sender")
        sender.execute("repository ${TuiWords.quote(from.toString())}")
        assertFalse(sender.execute("world-publish --source ${TuiWords.quote(source.toString())} --closed").error)
        receiver.execute("repository ${TuiWords.quote(destination.toString())}")
        val pool = Executors.newSingleThreadExecutor()
        var control = OperationControl()
        try {
            val port = ServerSocket(0).use { it.localPort }
            val receive = "world-receive --host 127.0.0.1 --port $port"
            val waiting = CountDownLatch(1)
            val cancelled = pool.submit<TuiState> {
                receiver.execute(receive, { if (it.contains("Ожидание подключения")) waiting.countDown() }, control)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertTrue(control.requestCancel())
            control.closeCancelledResources()
            val interrupted = cancelled.get(5, TimeUnit.SECONDS)
            assertFalse(interrupted.error)
            assertTrue(interrupted.cancelled)
            assertTrue(interrupted.output.contains("отменена"))
            assertEquals(null, WorldRepository(destination).current())

            control = OperationControl()
            val ready = CountDownLatch(1)
            var activationProtected = false
            val received = pool.submit<TuiState> {
                receiver.execute(receive, {
                    if (it.contains("Ожидание подключения")) ready.countDown()
                    if (it.lineSequence().last().contains("сборка и активация")) activationProtected = !control.requestCancel()
                }, control)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val sent = sender.execute("world-send --host 127.0.0.1 --port $port")
            assertFalse(sent.error, sent.output)
            val result = received.get(10, TimeUnit.SECONDS)
            assertFalse(result.error, result.output)
            assertTrue(result.output.contains("Обработано блоков"))
            assertTrue(activationProtected)
            assertEquals(WorldRepository(from).current(), WorldRepository(destination).current())
            assertEquals("world over TCP", Files.readString(destination.resolve("world/level.dat")))
        } finally {
            control.requestCancel()
            control.closeCancelledResources()
            pool.shutdownNow()
        }
    }

    @Test
    fun `TUI defaults pass through CLI validation and never create a profile implicitly`() {
        val operations = TuiOperations()
        val profile = root.resolve("profile")
        val repo = root.resolve("repo")
        val options = operations.options("world-send", listOf("--peer", "alex"), repo, profile)
        assertTrue(options.iroh)
        assertEquals(profile.toString(), options.profile)
        assertEquals(repo.toString(), options.repository)
        assertFailsWith<IllegalArgumentException> {
            operations.options("world-send", listOf("--peer", "alex", "--host", "localhost"), repo, profile)
        }
        val session = TuiSession()
        session.execute("profile ${TuiWords.quote(profile.toString())}")
        assertTrue(session.execute("world-receive --iroh --repository ${TuiWords.quote(repo.toString())}").error)
        assertFalse(Files.exists(profile))
        assertFalse(Files.exists(repo))
    }
}
