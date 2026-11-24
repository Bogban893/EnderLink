package org.enderlink.cli.tui

import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiSessionTest {
    @TempDir lateinit var root: Path

    @Test
    fun `theme commands validate names and complete without touching world or profile`() {
        val session = TuiSession(readWorld = { error("unexpected read") }, readProfile = { error("unexpected read") })
        assertEquals(TuiThemeId.MONO, session.state.theme)
        assertEquals(TuiThemeId.ENDER, session.execute("theme ender").theme)
        assertEquals(TuiThemeId.BTOP, session.execute("theme btop").theme)
        assertTrue(session.execute("theme unknown").error)
        assertEquals(TuiThemeId.BTOP, session.state.theme)
        assertEquals(TuiThemeId.BTOP, session.execute("theme").theme)
        assertTrue(session.state.output.contains("theme mono"))
        assertEquals(listOf("theme ender"), TuiCommands.completions("theme en"))
        assertEquals(TuiThemeId.MONO, session.execute("theme mono").theme)
    }

    @Test
    fun `quoted Windows paths survive parsing and incomplete input is not executed`() {
        assertEquals(listOf("C:\\Игры\\Мой мир\\"), TuiCommands.parse("repository \"C:\\Игры\\Мой мир\\\"").second)
        var reads = 0
        val session = TuiSession(readWorld = { reads++; "world" })
        assertTrue(session.execute("repository \"unfinished").output.contains("кавычку"))
        assertEquals(0, reads)
        assertTrue(TuiCommands.hint("repository ").contains("<путь>"))
        assertEquals(listOf("world-status"), TuiCommands.completions("wor"))
        assertTrue(session.execute("exit extra").output.contains("Использование"))
        assertFalse(session.state.close)
    }

    @Test
    fun `selecting and refreshing real repository keeps source untouched`() {
        val source = Files.createDirectory(root.resolve("Исходный мир"))
        Files.writeString(source.resolve("level.dat"), "test world")
        val repositoryPath = root.resolve("Репозиторий мира")
        val version = WorldRepository(repositoryPath).publish(source)
        val session = TuiSession()
        val selected = session.execute("repository \"$repositoryPath\"")
        assertEquals(repositoryPath, selected.repository)
        assertTrue(selected.world.contains(version.versionId))
        assertTrue(session.execute("world-status").world.contains(version.worldId))
        assertFalse(Files.exists(source.resolve(".enderlink")))
        assertTrue(session.execute("repository \"$source\"").output.startsWith("Ошибка:"))
        assertEquals(repositoryPath, session.state.repository)
        assertEquals("test world", Files.readString(source.resolve("level.dat")))
    }

    @Test
    fun `friends use the selected profile and failures do not replace displayed data`() {
        var fail = false
        val session = TuiSession(readProfile = {
            if (fail) throw java.io.IOException("Профиль занят")
            "public-id" to mapOf("alex" to "friend-id")
        })
        assertTrue(session.execute("friends").friends.contains("alex — доступность неизвестна"))
        assertTrue(session.state.output.contains("public-id"))
        fail = true
        val failed = session.execute("friends")
        assertTrue(failed.output.contains("Профиль занят"))
        assertTrue(failed.friends.contains("alex"))
        assertTrue(session.execute("exit").close)
    }
}
