package org.enderlink.agent

import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@Timeout(20)
class HostServiceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `фоновый процесс публикует мир после штатного выхода сервера`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "original world")
        val repository = WorldRepository(root.resolve("repository"))
        val initial = repository.publish(source).versionId
        val session = Files.createDirectory(root.resolve("session"))
        Files.writeString(session.resolve("eula.txt"), "eula=true\n")
        val server = VanillaServer(repository, session, initial)
        server.prepare()
        HostService.run(server, fakeCommand("exit"))
        assertNotEquals(initial, repository.current()?.versionId)
        assertEquals("updated world", Files.readString(repository.workingDirectory.resolve("level.dat")))
    }

    @Test
    fun `ошибка фонового сервера сохраняет исходную версию`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "original world")
        val repository = WorldRepository(root.resolve("repository"))
        val initial = repository.publish(source).versionId
        val session = Files.createDirectory(root.resolve("session"))
        Files.writeString(session.resolve("eula.txt"), "eula=true\n")
        val server = VanillaServer(repository, session, initial)
        server.prepare()
        assertFailsWith<IOException> { HostService.run(server, fakeCommand("fail")) }
        assertEquals(initial, repository.current()?.versionId)
        assertEquals("original world", Files.readString(server.worldDirectory.resolve("level.dat")))
    }

    private fun fakeCommand(mode: String): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = FakeServerProcess::class.java.protectionDomain.codeSource.location.toURI().path
        return listOf(java, "-cp", classes, FakeServerProcess::class.java.name, mode)
    }
}
