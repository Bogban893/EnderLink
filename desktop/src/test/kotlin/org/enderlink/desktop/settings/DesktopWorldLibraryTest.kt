package org.enderlink.desktop.settings

import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopWorldLibraryTest {
    @TempDir lateinit var root: Path

    @Test
    fun `new worlds get separate persistent repositories and names`() {
        val library = DesktopWorldLibrary(root.resolve("library"))
        library.prepare(root.resolve("minecraft/saves"))
        val first = library.create()
        val second = library.create()
        assertTrue(first != second)
        assertTrue(Files.isDirectory(first.resolve(".enderlink")))
        Files.createDirectories(first.resolve("world"))
        Files.writeString(first.resolve("world/level.dat"), "world")
        val id = WorldRepository(first).publish(first.resolve("world")).worldId
        library.name(first, "Мой мир")

        val reopened = DesktopWorldLibrary(root.resolve("library"))
        assertEquals(listOf(first, second).sorted(), reopened.repositories())
        assertEquals("Мой мир", reopened.name(first))
        assertEquals(id, WorldRepository(first).current()!!.worldId)
        assertEquals(null, WorldRepository(second).current())
    }

    @Test
    fun `library location cannot overlap minecraft saves`() {
        val saves = Files.createDirectories(root.resolve("minecraft/saves"))
        assertFailsWith<IOException> { DesktopWorldLibrary(saves.resolve("worlds")).prepare(saves) }
        assertFailsWith<IOException> { DesktopWorldLibrary(root.resolve("minecraft")).prepare(saves) }
        assertTrue(Files.notExists(saves.resolve("worlds")))
    }

    @Test
    fun `moving library verifies copies and leaves originals as backup`() {
        val saves = root.resolve("minecraft/saves")
        val old = DesktopWorldLibrary(root.resolve("old"))
        old.prepare(saves)
        val existing = old.create()
        Files.createDirectories(existing.resolve("world"))
        Files.writeString(existing.resolve("world/level.dat"), "saved world")
        val version = WorldRepository(existing).publish(existing.resolve("world")).versionId
        old.name(existing, "Исходный мир")
        val next = DesktopWorldLibrary(root.resolve("new"))
        val moved = old.copyTo(next, saves)
        val created = moved.getValue(existing)
        assertEquals(listOf(existing), old.repositories())
        assertEquals(listOf(created), next.repositories())
        assertEquals(version, WorldRepository(created).current()!!.versionId)
        assertEquals("Исходный мир", next.name(created))
        assertEquals("saved world", Files.readString(existing.resolve("world/level.dat")))
    }

    @Test
    fun `move refuses modified source or occupied destination without overwriting`() {
        val saves = root.resolve("minecraft/saves")
        val old = DesktopWorldLibrary(root.resolve("old"))
        old.prepare(saves)
        val existing = old.create()
        Files.createDirectories(existing.resolve("world"))
        Files.writeString(existing.resolve("world/level.dat"), "original")
        WorldRepository(existing).publish(existing.resolve("world"))
        val next = DesktopWorldLibrary(root.resolve("new"))
        next.prepare(saves)
        assertFailsWith<IOException> { old.copyTo(DesktopWorldLibrary(existing.resolve("nested")), saves) }
        assertTrue(Files.notExists(existing.resolve("nested")))
        val collision = Files.createDirectory(next.directory.resolve(existing.fileName))
        Files.writeString(collision.resolve("sentinel"), "keep")
        assertFailsWith<IOException> { old.copyTo(next, saves) }
        assertEquals("keep", Files.readString(collision.resolve("sentinel")))
        Files.delete(collision.resolve("sentinel"))
        Files.delete(collision)
        Files.writeString(existing.resolve("world/level.dat"), "local change")
        assertFailsWith<IOException> { old.copyTo(next, saves) }
        assertTrue(Files.notExists(next.directory.resolve(existing.fileName)))
        assertEquals("local change", Files.readString(existing.resolve("world/level.dat")))
    }
}
