package org.enderlink.world

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldManifestTest {
    @TempDir lateinit var root: Path
    private val worldId = WorldManifest.newWorldId()

    @Test
    fun `дерево и история имеют разные идентификаторы`() {
        val world = Files.createDirectory(root.resolve("world"))
        Files.createDirectories(world.resolve("empty/nested"))
        Files.writeString(world.resolve("level.dat"), "one")
        val first = WorldScanner.scan(world, worldId)
        assertEquals(first, WorldScanner.scan(world, worldId))
        val sameTreeNextVersion = WorldScanner.scan(world, worldId, first.versionId)
        assertEquals(first.treeHash, sameTreeNextVersion.treeHash)
        assertNotEquals(first.versionId, sameTreeNextVersion.versionId)
        assertTrue(first.entries.any { it.path == "empty/nested" && it.type == WorldEntryType.DIRECTORY })
        assertEquals(first.treeHash, WorldManifest.create(worldId, null, first.entries.reversed()).treeHash)
    }

    @Test
    fun `добавление изменение и удаление входят в полный состав`() {
        val world = Files.createDirectory(root.resolve("world"))
        Files.writeString(world.resolve("old"), "old")
        Files.writeString(world.resolve("change"), "before")
        val before = WorldScanner.scan(world, worldId)
        Files.delete(world.resolve("old"))
        Files.writeString(world.resolve("change"), "after")
        Files.writeString(world.resolve("new"), "new")
        val after = WorldScanner.scan(world, worldId, before.versionId)
        assertNotEquals(before.treeHash, after.treeHash)
        assertEquals(listOf("change", "new"), after.entries.map { it.path })
        assertEquals(before.versionId, after.parentVersionId)
    }

    @Test
    fun `опасные пути и ссылки отклоняются`() {
        val world = Files.createDirectory(root.resolve("world"))
        for (path in listOf("../escape", "/absolute", "a/../escape", "a\\escape", "C:/drive", "a//b")) {
            assertFailsWith<IOException> { WorldManifest.create(worldId, null, listOf(WorldEntry(path, WorldEntryType.DIRECTORY))) }
        }
        Files.createSymbolicLink(world.resolve("secret"), Files.writeString(root.resolve("outside"), "private"))
        assertFailsWith<IOException> { WorldScanner.scan(world, worldId) }
    }

    @Test
    fun `большой файл читается потоково`() {
        val world = Files.createDirectory(root.resolve("world"))
        val large = world.resolve("large.mca")
        Files.newOutputStream(large).use { output ->
            val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
            repeat(20) { output.write(block) }
        }
        val manifest = WorldScanner.scan(world, worldId)
        assertEquals(20L * 1024 * 1024, manifest.entries.single().size)
        assertEquals(80, manifest.entries.single().blocks.size)
    }

    @Test
    fun `список блоков включен в ID версии`() {
        val world = Files.createDirectory(root.resolve("world"))
        Files.write(world.resolve("region.mca"), ByteArray(WorldManifest.BLOCK_SIZE + 7) { it.toByte() })
        val original = WorldScanner.scan(world, worldId)
        val entry = original.entries.single()
        val changed = entry.copy(blocks = entry.blocks.toMutableList().also {
            it[0] = it[0].copy(hash = "sha256:" + "0".repeat(64))
        })
        val tampered = original.copy(entries = listOf(changed))
        assertEquals(original.treeHash, WorldManifest.create(worldId, null, listOf(changed)).treeHash)
        assertFailsWith<IOException> { tampered.validate() }
    }
}
