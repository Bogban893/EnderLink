package org.enderlink.world

import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import java.io.IOException

class PlayerDataInspectorTest {
    @TempDir lateinit var root: Path

    @Test
    fun `определяет вложенные данные старого одиночного мира без номера версии`() {
        writeLevel(withPlayer = true, withUuid = false)
        val result = PlayerDataInspector.inspect(root)
        assertTrue(result.levelReadable)
        assertTrue(result.legacyEmbeddedPlayer)
        assertFalse(result.singleplayerUuid)
    }

    @Test
    fun `определяет новую ссылку на игрока и оба расположения файлов`() {
        writeLevel(withPlayer = false, withUuid = true)
        Files.createDirectories(root.resolve("playerdata"))
        Files.createDirectories(root.resolve("players/data"))
        Files.writeString(root.resolve("playerdata/old.dat"), "old")
        Files.writeString(root.resolve("players/data/new.dat"), "new")
        val result = PlayerDataInspector.inspect(root)
        assertTrue(result.levelReadable)
        assertTrue(result.singleplayerUuid)
        assertFalse(result.legacyEmbeddedPlayer)
        assertEquals(1, result.legacyPlayerFiles)
        assertEquals(1, result.modernPlayerFiles)
    }

    @Test
    fun `не принимает неполный level dat за мир без встроенного игрока`() {
        Files.writeString(root.resolve("level.dat"), "invalid")
        assertFalse(PlayerDataInspector.inspect(root).levelReadable)
    }

    @Test
    fun `импорт владельца создает новую версию и не трогает исходный мир`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = true, withUuid = false, world = source)
        val original = Files.readAllBytes(source.resolve("level.dat"))
        val repository = WorldRepository(root.resolve("repository"))
        val first = repository.publish(source)
        val owner = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val second = repository.importLegacyPlayer(owner)
        assertTrue(first.versionId != second.versionId)
        assertEquals(first.versionId, second.parentVersionId)
        assertContentEquals(original, Files.readAllBytes(source.resolve("level.dat")))
        assertFalse(Files.exists(source.resolve("playerdata")))
        val player = repository.workingDirectory.resolve("playerdata/$owner.dat")
        val data = GZIPInputStream(Files.newInputStream(player)).use { it.readAllBytes() }
        assertContentEquals(byteArrayOf(10, 0, 0), data.copyOfRange(0, 3))
        assertTrue(String(data, Charsets.ISO_8859_1).contains("minecraft:diamond"))
        assertEquals(second.versionId, repository.current()?.versionId)
        assertFailsWith<IOException> { repository.publish(source) }
        assertEquals(second.versionId, repository.current()?.versionId)
        assertFailsWith<IOException> { repository.importLegacyPlayer(owner) }
        assertEquals(second.versionId, repository.current()?.versionId)
    }

    @Test
    fun `новый формат нельзя импортировать как старый профиль`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = false, withUuid = true, world = source)
        val repository = WorldRepository(root.resolve("repository"))
        val first = repository.publish(source)
        assertFailsWith<IOException> { repository.importLegacyPlayer(UUID.randomUUID()) }
        assertEquals(first.versionId, repository.current()?.versionId)
        assertFalse(Files.exists(repository.workingDirectory.resolve("playerdata")))
    }

    @Test
    fun `публикация разрешает перенос файла UUID в новое расположение`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = true, withUuid = false, world = source)
        val repository = WorldRepository(root.resolve("repository"))
        repository.publish(source)
        val owner = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        repository.importLegacyPlayer(owner)
        val modernDirectory = Files.createDirectories(source.resolve("players/data"))
        Files.copy(repository.workingDirectory.resolve("playerdata/$owner.dat"), modernDirectory.resolve("$owner.dat"))
        val converted = repository.publish(source)
        assertTrue(converted.entries.any { it.path == "players/data/$owner.dat" })
        assertFalse(Files.exists(repository.workingDirectory.resolve("playerdata/$owner.dat")))
    }

    private fun writeLevel(withPlayer: Boolean, withUuid: Boolean, world: Path = root) {
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))).use { output ->
            output.writeByte(10)
            output.writeUTF("")
            output.writeByte(10)
            output.writeUTF("Data")
            // Nested data ensures the scanner skips unrelated tags instead of searching raw bytes.
            output.writeByte(10)
            output.writeUTF("Other")
            output.writeByte(8)
            output.writeUTF("name")
            output.writeUTF("Player")
            output.writeByte(0)
            if (withPlayer) {
                output.writeByte(10)
                output.writeUTF("Player")
                output.writeByte(9)
                output.writeUTF("Inventory")
                output.writeByte(10)
                output.writeInt(1)
                output.writeByte(8)
                output.writeUTF("id")
                output.writeUTF("minecraft:diamond")
                output.writeByte(0)
                output.writeByte(0)
            }
            if (withUuid) {
                output.writeByte(11)
                output.writeUTF("singleplayer_uuid")
                output.writeInt(4)
                repeat(4) { output.writeInt(it) }
            }
            output.writeByte(0)
            output.writeByte(0)
        }
    }
}
