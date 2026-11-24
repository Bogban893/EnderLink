package org.enderlink.cli.tui

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** Appearance settings are separate from the locked network profile. */
internal class TuiThemeSettings(private val path: Path) {
    fun load(): TuiThemeId = TuiThemeId.fromId(read().getProperty("theme", "mono")) ?: TuiThemeId.MONO

    fun save(theme: TuiThemeId) {
        val properties = read().apply { setProperty("theme", theme.id) }
        val destination = path.toAbsolutePath().normalize()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".cli-settings-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, "EnderLink CLI") }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(): Properties = Properties().apply {
        if (Files.exists(path)) Files.newBufferedReader(path).use { load(it) }
    }

    companion object {
        fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".enderlink", "cli.properties")
    }
}
