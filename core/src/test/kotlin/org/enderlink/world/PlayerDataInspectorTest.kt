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

    @Test
    fun `цикл старого одиночного мира сохраняет данные обоих владельцев`() {
        val ownerA = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val ownerB = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = true, withUuid = false, world = source, item = "minecraft:diamond")
        val original = Files.readAllBytes(source.resolve("level.dat"))
        val repo = WorldRepository(root.resolve("repository"))
        val first = repo.publishSingleplayer(source, ownerA)
        assertContentEquals(original, Files.readAllBytes(source.resolve("level.dat")))
        val saveB = Files.createDirectory(root.resolve("save-b"))
        copyWorld(repo.workingDirectory, saveB)
        PlayerDataInspector.prepareSingleplayerOwner(saveB, ownerB)
        assertFalse(PlayerDataInspector.inspect(saveB).legacyEmbeddedPlayer)
        assertTrue(String(readPlayer(saveB.resolve("playerdata/$ownerA.dat")), Charsets.ISO_8859_1).contains("minecraft:diamond"))

        // Simulate B closing Minecraft after obtaining a different item.
        writeLevel(withPlayer = true, withUuid = false, world = saveB, item = "minecraft:emerald")
        val second = repo.publishSingleplayer(saveB, ownerB)
        assertEquals(first.versionId, second.parentVersionId)
        assertTrue(String(readPlayer(repo.workingDirectory.resolve("playerdata/$ownerB.dat")), Charsets.ISO_8859_1).contains("minecraft:emerald"))
        val saveA = Files.createDirectory(root.resolve("save-a"))
        copyWorld(repo.workingDirectory, saveA)
        PlayerDataInspector.prepareSingleplayerOwner(saveA, ownerA)
        assertTrue(String(PlayerDataInspector.extractLegacyPlayer(saveA), Charsets.ISO_8859_1).contains("minecraft:diamond"))
        assertTrue(String(readPlayer(saveA.resolve("playerdata/$ownerB.dat")), Charsets.ISO_8859_1).contains("minecraft:emerald"))
        assertContentEquals(original, Files.readAllBytes(source.resolve("level.dat")))
    }

    @Test
    fun `подготовка отклоняет мир без записи прежнего владельца`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = true, withUuid = false, world = source)
        val before = Files.readAllBytes(source.resolve("level.dat"))
        assertFailsWith<IOException> { PlayerDataInspector.prepareSingleplayerOwner(source, UUID.randomUUID()) }
        assertContentEquals(before, Files.readAllBytes(source.resolve("level.dat")))
    }

    @Test
    fun `новый формат переключает ссылку UUID без изменения файлов игроков`() {
        val owner = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        writeLevel(withPlayer = false, withUuid = true)
        val playerDirectory = Files.createDirectories(root.resolve("players/data"))
        val player = playerDirectory.resolve("$owner.dat")
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(player))).use { output ->
            output.writeByte(10); output.writeUTF(""); output.writeByte(0)
        }
        val originalPlayer = Files.readAllBytes(player)
        PlayerDataInspector.prepareSingleplayerOwner(root, owner)
        val raw = GZIPInputStream(Files.newInputStream(root.resolve("level.dat"))).use { it.readAllBytes() }
        val uuidBytes = java.nio.ByteBuffer.allocate(16)
            .putLong(owner.mostSignificantBits).putLong(owner.leastSignificantBits).array()
        assertTrue(raw.asList().windowed(16).any { it.toByteArray().contentEquals(uuidBytes) })
        assertContentEquals(originalPlayer, Files.readAllBytes(player))
    }

    @Test
    fun `повреждённый файл нового владельца не меняет level dat`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(withPlayer = true, withUuid = false, world = source)
        PlayerDataInspector.captureLegacyOwner(source, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
        val ownerB = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        Files.writeString(source.resolve("playerdata/$ownerB.dat"), "broken")
        val before = Files.readAllBytes(source.resolve("level.dat"))
        assertFailsWith<IOException> { PlayerDataInspector.prepareSingleplayerOwner(source, ownerB) }
        assertContentEquals(before, Files.readAllBytes(source.resolve("level.dat")))
    }

    private fun readPlayer(path: Path): ByteArray = GZIPInputStream(Files.newInputStream(path)).use { it.readAllBytes() }

    private fun copyWorld(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.filter { it != source }.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
    }

    private fun writeLevel(withPlayer: Boolean, withUuid: Boolean, world: Path = root, item: String = "minecraft:diamond") {
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
                output.writeUTF(item)
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
