package org.enderlink.desktop.minecraft

import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftSavesTest {
    @TempDir lateinit var root: Path

    private fun repository(): WorldRepository {
        val repo = WorldRepository(root.resolve("repository"))
        Files.createDirectories(repo.workingDirectory)
        Files.writeString(repo.workingDirectory.resolve("level.dat"), "version one")
        Files.writeString(repo.workingDirectory.resolve("unchanged.dat"), "same bytes")
        repo.publish(repo.workingDirectory)
        return repo
    }

    @Test
    fun `received world appears in saves and later version updates same folder`() {
        val repo = repository()
        val saves = root.resolve("minecraft/saves")
        val first = MinecraftSaves.install(repo, saves, "My World")
        assertEquals("version one", Files.readString(first.directory.resolve("level.dat")))
        assertEquals(repo.current()!!.versionId, first.versionId)
        assertTrue(Files.isRegularFile(first.directory.resolve("session.lock")))

        // Minecraft changes session.lock even when no blocks in the world have changed.
        Files.writeString(first.directory.resolve("session.lock"), "closed session")
        Files.writeString(repo.workingDirectory.resolve("level.dat"), "version two")
        repo.publish(repo.workingDirectory)
        val next = MinecraftSaves.install(repo, saves, "My World")
        assertEquals(first.directory, next.directory)
        assertEquals("version two", Files.readString(next.directory.resolve("level.dat")))
        assertEquals("same bytes", Files.readString(next.directory.resolve("unchanged.dat")))
        assertTrue(next.reusedFiles >= 1)
        assertFalse(Files.exists(saves.resolve(".enderlink-sync/My World.backup")))
        assertFalse(Files.exists(saves.resolve(".enderlink-sync/My World.pending")))
    }

    @Test
    fun `local changes and open game are never overwritten`() {
        val repo = repository()
        val saves = root.resolve("saves")
        val installed = MinecraftSaves.install(repo, saves, "World")
        Files.writeString(repo.workingDirectory.resolve("level.dat"), "incoming update")
        repo.publish(repo.workingDirectory)
        Files.writeString(installed.directory.resolve("level.dat"), "locally played world")
        assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World") }
        assertEquals("locally played world", Files.readString(installed.directory.resolve("level.dat")))

        Files.writeString(installed.directory.resolve("level.dat"), "version one")
        FileChannel.open(installed.directory.resolve("session.lock"), WRITE).use { channel ->
            channel.lock().use {
                assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World") }
            }
        }
        assertEquals("version one", Files.readString(installed.directory.resolve("level.dat")))
    }

    @Test
    fun `unrelated preexisting world is not replaced`() {
        val repo = repository()
        val saves = Files.createDirectory(root.resolve("saves"))
        val other = Files.createDirectory(saves.resolve("World"))
        Files.writeString(other.resolve("level.dat"), "somebody else's world")
        assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World") }
        assertEquals("somebody else's world", Files.readString(other.resolve("level.dat")))
    }

    @Test
    fun `interrupted swap restores previous save before retry`() {
        val repo = repository()
        val saves = root.resolve("saves")
        val first = MinecraftSaves.install(repo, saves, "World")
        val backup = saves.resolve(".enderlink-sync/World.backup")
        Files.move(first.directory, backup)
        val recovered = MinecraftSaves.install(repo, saves, "World")
        assertEquals(first.directory, recovered.directory)
        assertEquals("version one", Files.readString(recovered.directory.resolve("level.dat")))
        assertFalse(Files.exists(backup))
    }
}
