package org.enderlink.desktop.minecraft

import org.enderlink.world.WorldRepository
import org.enderlink.world.PlayerDataInspector
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftSavesTest {
    @TempDir lateinit var root: Path

    @Test
    fun `open source world cannot be imported while lock is held`() {
        val source = Files.createDirectories(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "world")
        val session = source.resolve("session.lock")
        Files.writeString(session, "session")
        FileChannel.open(session, WRITE).use { channel ->
            channel.lock().use { assertFailsWith<IOException> { MinecraftSaves.requireClosed(source) } }
        }
        MinecraftSaves.requireClosed(source)
    }

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
    fun `managed save inspection detects play without changing the save`() {
        val repo = repository()
        val saves = root.resolve("saves")
        val installed = MinecraftSaves.install(repo, saves, "World")
        assertEquals(ManagedSaveState.UNCHANGED, MinecraftSaves.inspectManaged(repo, saves).single().state)

        Files.writeString(installed.directory.resolve("session.lock"), "closed session")
        assertEquals(ManagedSaveState.UNCHANGED, MinecraftSaves.inspectManaged(repo, saves).single().state)
        Files.writeString(installed.directory.resolve("level.dat"), "played version")
        assertEquals(ManagedSaveState.CHANGED, MinecraftSaves.inspectManaged(repo, saves).single().state)
        assertEquals("played version", Files.readString(installed.directory.resolve("level.dat")))

        FileChannel.open(installed.directory.resolve("session.lock"), WRITE).use { channel ->
            channel.lock().use {
                assertEquals(ManagedSaveState.OPEN, MinecraftSaves.inspectManaged(repo, saves).single().state)
            }
        }
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

    @Test
    fun `installed legacy world gives the chosen owner their own player data`() {
        val ownerA = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val ownerB = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val source = Files.createDirectory(root.resolve("source"))
        writeLegacyLevel(source.resolve("level.dat"), "minecraft:diamond")
        val players = Files.createDirectory(source.resolve("playerdata"))
        writePlayer(players.resolve("$ownerB.dat"), "minecraft:emerald")
        val repo = WorldRepository(root.resolve("repository"))
        repo.publishSingleplayer(source, ownerA)
        val saves = root.resolve("saves")
        val installed = MinecraftSaves.install(repo, saves, "World", owner = ownerB)
        assertTrue(String(PlayerDataInspector.extractLegacyPlayer(installed.directory), Charsets.ISO_8859_1)
            .contains("minecraft:emerald"))
        assertTrue(String(PlayerDataInspector.extractLegacyPlayer(repo.workingDirectory), Charsets.ISO_8859_1)
            .contains("minecraft:diamond"))
        assertTrue(Files.exists(installed.directory.resolve("playerdata/$ownerA.dat")))
        MinecraftSaves.install(repo, saves, "World", owner = ownerB)
        Files.move(installed.directory, saves.resolve(".enderlink-sync/World.backup"))
        MinecraftSaves.install(repo, saves, "World", owner = ownerB)
        writeLegacyLevel(installed.directory.resolve("level.dat"), "minecraft:gold_ingot")
        assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World", owner = ownerB) }
    }

    private fun writeLegacyLevel(path: Path, item: String) {
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(path))).use { output ->
            output.writeByte(10); output.writeUTF("")
            output.writeByte(10); output.writeUTF("Data")
            output.writeByte(10); output.writeUTF("Player")
            output.writeByte(8); output.writeUTF("Item"); output.writeUTF(item)
            output.writeByte(0); output.writeByte(0); output.writeByte(0)
        }
    }

    private fun writePlayer(path: Path, item: String) {
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(path))).use { output ->
            output.writeByte(10); output.writeUTF("")
            output.writeByte(8); output.writeUTF("Item"); output.writeUTF(item)
            output.writeByte(0)
        }
    }
}
