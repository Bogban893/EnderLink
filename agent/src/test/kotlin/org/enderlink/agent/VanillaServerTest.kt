package org.enderlink.agent

import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Timeout(20)
class VanillaServerTest {
    @TempDir lateinit var root: Path

    private fun repository(): Pair<WorldRepository, String> {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "original world")
        val repository = WorldRepository(root.resolve("repository"))
        return repository to repository.publish(source).versionId
    }

    @Test
    fun `сервер получает проверенную копию и публикует мир после штатной остановки`() {
        val (repository, initial) = repository()
        val session = Files.createDirectory(root.resolve("session"))
        Files.writeString(session.resolve("eula.txt"), "eula=true\n")
        val server = VanillaServer(repository, session, initial)
        server.prepare()
        assertEquals("original world", Files.readString(server.worldDirectory.resolve("level.dat")))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = FakeServerProcess::class.java.protectionDomain.codeSource.location.toURI().path
        server.start(listOf(java, "-cp", classes, FakeServerProcess::class.java.name))
        assertTrue(server.isRunning())
        server.save()
        val next = server.stopAndPublish()
        assertNotEquals(initial, next)
        assertFalse(server.isRunning())
        assertEquals("updated world", Files.readString(repository.workingDirectory.resolve("level.dat")))
        assertEquals(next, repository.current()?.versionId)
    }

    @Test
    fun `не запускает неподготовленный или занятый каталог`() {
        val (repository, initial) = repository()
        val session = Files.createDirectory(root.resolve("session"))
        val server = VanillaServer(repository, session, initial)
        assertFailsWith<IOException> { server.prepare() }
        Files.writeString(session.resolve("eula.txt"), "eula=true\n")
        Files.writeString(session.resolve("server.properties"), "level-name=another\n")
        assertFailsWith<IOException> { server.prepare() }
        Files.writeString(session.resolve("server.properties"), "level-name=world\n")
        server.prepare()
        assertFailsWith<IOException> { VanillaServer(repository, session, initial).prepare() }
        assertFailsWith<IOException> { VanillaServer(repository, root.resolve("repository/nested"), initial).prepare() }
    }

    @Test
    fun `ошибка запуска видна и не публикует непроверенный мир`() {
        val (repository, initial) = repository()
        val session = Files.createDirectory(root.resolve("failed-session"))
        Files.writeString(session.resolve("eula.txt"), "eula=true\n")
        val server = VanillaServer(repository, session, initial)
        server.prepare()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = FakeServerProcess::class.java.protectionDomain.codeSource.location.toURI().path
        server.start(listOf(java, "-cp", classes, FakeServerProcess::class.java.name, "fail"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(server.isRunning())
        assertTrue(server.status().contains("код 7"))
        assertFailsWith<IOException> { server.stopAndPublish() }
        assertEquals(initial, repository.current()?.versionId)
        assertTrue(Files.exists(server.worldDirectory))
    }
}
