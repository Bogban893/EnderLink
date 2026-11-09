package org.enderlink.world

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldRepositoryTest {
    @TempDir lateinit var root: Path

    private fun prepare(): Pair<WorldRepository, WorldRepository> {
        val a = WorldRepository(root.resolve("a"))
        val b = WorldRepository(root.resolve("b"))
        Files.createDirectories(a.workingDirectory.resolve("empty/nested"))
        Files.writeString(a.workingDirectory.resolve("level.dat"), "level-one")
        Files.writeString(a.workingDirectory.resolve("shared"), "unchanged")
        val first = a.publish(a.workingDirectory)
        b.importFrom(a, first)
        b.activate(first)
        return a to b
    }

    private fun assertSameTree(a: WorldRepository, b: WorldRepository) {
        val id = a.current()!!.worldId
        assertEquals(WorldScanner.scan(a.workingDirectory, id).treeHash, WorldScanner.scan(b.workingDirectory, id).treeHash)
    }

    @Test
    fun `первая публикация обновление удаления и восстановление`() {
        val (a, b) = prepare()
        val first = a.current()!!
        assertEquals(first, a.publish(a.workingDirectory))
        assertSameTree(a, b)
        Files.delete(a.workingDirectory.resolve("level.dat"))
        Files.writeString(a.workingDirectory.resolve("new.dat"), "new")
        val second = a.publish(a.workingDirectory)
        b.importFrom(a, second)
        val plan = b.plan(second)
        assertEquals(listOf("level.dat"), plan.delete)
        assertEquals(listOf("new.dat"), plan.createOrReplace.map { it.path })
        b.activate(second)
        assertSameTree(a, b)
        assertFalse(Files.exists(b.workingDirectory.resolve("level.dat")))
        assertTrue(Files.isDirectory(b.workingDirectory.resolve("empty/nested")))
        assertTrue(Files.exists(root.resolve("b/.enderlink/previous/level.dat")))
        // Одинаковый файл хранится одним объектом между версиями.
        Files.list(root.resolve("b/.enderlink/objects")).use { assertEquals(3L, it.count()) }
        b.checkout(first.versionId)
        assertEquals("level-one", Files.readString(b.workingDirectory.resolve("level.dat")))
        assertFalse(Files.exists(b.workingDirectory.resolve("new.dat")))
    }

    @Test
    fun `поврежденный объект не активируется`() {
        val (a, b) = prepare()
        Files.writeString(a.workingDirectory.resolve("level.dat"), "changed")
        val second = a.publish(a.workingDirectory)
        b.importFrom(a, second)
        val entry = second.entries.single { it.path == "level.dat" }
        Files.writeString(root.resolve("b/.enderlink/objects/${entry.hash!!.removePrefix("sha256:")}"), "damage")
        assertFailsWith<IOException> { b.activate(second) }
        assertEquals("level-one", Files.readString(b.workingDirectory.resolve("level.dat")))
        assertEquals(second.parentVersionId, b.current()!!.versionId)
    }

    @Test
    fun `независимые изменения сохраняют обе ветки`() {
        val (a, b) = prepare()
        Files.writeString(a.workingDirectory.resolve("level.dat"), "A")
        val fromA = a.publish(a.workingDirectory)
        Files.writeString(b.workingDirectory.resolve("level.dat"), "B")
        val fromB = b.publish(b.workingDirectory)
        b.importFrom(a, fromA)
        assertFailsWith<WorldConflictException> { b.activate(fromA) }
        assertEquals("B", Files.readString(b.workingDirectory.resolve("level.dat")))
        assertEquals(fromB.versionId, b.current()!!.versionId)
        assertEquals(fromA, b.load(fromA.versionId))
        assertEquals(fromB, b.load(fromB.versionId))
    }

    @Test
    fun `прерванное переключение восстанавливает старую копию`() {
        val (a, b) = prepare()
        val first = b.current()!!
        Files.writeString(a.workingDirectory.resolve("level.dat"), "changed")
        val second = a.publish(a.workingDirectory)
        b.importFrom(a, second)
        val meta = root.resolve("b/.enderlink")
        Files.writeString(meta.resolve("pending"), second.versionId + "\n")
        Files.move(b.workingDirectory, meta.resolve("previous"), ATOMIC_MOVE)
        assertEquals(first.versionId, b.current()!!.versionId)
        assertEquals("level-one", Files.readString(b.workingDirectory.resolve("level.dat")))
        assertFalse(Files.exists(meta.resolve("pending")))
        b.activate(second)
        assertEquals("changed", Files.readString(b.workingDirectory.resolve("level.dat")))
    }

    @Test
    fun `перезапуск завершает уже переключенную проверенную версию`() {
        val (a, b) = prepare()
        Files.writeString(a.workingDirectory.resolve("level.dat"), "changed")
        val second = a.publish(a.workingDirectory)
        b.importFrom(a, second)
        val meta = root.resolve("b/.enderlink")
        Files.writeString(meta.resolve("pending"), second.versionId + "\n")
        Files.move(b.workingDirectory, meta.resolve("previous"), ATOMIC_MOVE)
        Files.createDirectory(b.workingDirectory)
        Files.createDirectories(b.workingDirectory.resolve("empty/nested"))
        Files.writeString(b.workingDirectory.resolve("level.dat"), "changed")
        Files.writeString(b.workingDirectory.resolve("shared"), "unchanged")
        assertEquals(second.versionId, b.current()!!.versionId)
        assertFalse(Files.exists(meta.resolve("pending")))
        assertEquals("level-one", Files.readString(meta.resolve("previous/level.dat")))
    }

    @Test
    fun `неопубликованные изменения не перезаписываются`() {
        val (a, b) = prepare()
        Files.writeString(b.workingDirectory.resolve("local-extra"), "not published")
        Files.writeString(a.workingDirectory.resolve("level.dat"), "changed")
        val second = a.publish(a.workingDirectory)
        b.importFrom(a, second)
        assertFailsWith<IOException> { b.activate(second) }
        assertTrue(Files.exists(b.workingDirectory.resolve("local-extra")))
        Files.delete(b.workingDirectory.resolve("local-extra"))
        b.activate(second)
        assertFalse(Files.exists(b.workingDirectory.resolve("local-extra")))
    }
}
