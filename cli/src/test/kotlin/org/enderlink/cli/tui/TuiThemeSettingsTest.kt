package org.enderlink.cli.tui

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiThemeSettingsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `selection survives a new session and preserves other settings`() {
        val path = root.resolve("cli.properties")
        val settings = TuiThemeSettings(path)
        assertEquals(TuiThemeId.MONO, settings.load())
        assertFalse(Files.exists(path))
        Files.writeString(path, "other=value\ntheme=unknown\n")
        assertEquals(TuiThemeId.MONO, settings.load())

        val first = TuiSession(themeSettings = settings)
        assertFalse(first.execute("theme ender").error)
        val second = TuiSession(themeSettings = TuiThemeSettings(path))
        assertEquals(TuiThemeId.ENDER, second.state.theme)
        assertFalse(second.execute("theme btop").error)
        assertEquals(TuiThemeId.BTOP, settings.load())
        val stored = Properties().apply { Files.newBufferedReader(path).use { load(it) } }
        assertEquals("value", stored.getProperty("other"))
        assertTrue(second.execute("theme unknown").error)
        assertEquals(TuiThemeId.BTOP, settings.load())
        Files.list(root).use { assertEquals(listOf(path), it.toList()) }
    }

    @Test
    fun `a failed save keeps the old theme and reports an error`() {
        val parent = root.resolve("settings")
        val settings = TuiThemeSettings(parent.resolve("cli.properties"))
        val session = TuiSession(themeSettings = settings)
        Files.writeString(parent, "not a directory")
        val state = session.execute("theme ender")
        assertTrue(state.error)
        assertEquals(TuiThemeId.MONO, state.theme)
        assertEquals("not a directory", Files.readString(parent))
    }
}
