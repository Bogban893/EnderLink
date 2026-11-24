package org.enderlink.desktop.settings

import org.enderlink.desktop.MinecraftSaves
import org.enderlink.worldtransfer.ParentFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal data class DesktopPreferences(
    val repository: String = "",
    val saves: String = MinecraftSaves.defaultDirectory().toString(),
    val saveName: String = "EnderLink World",
    val serverBase: String = "",
    val jar: String = "",
    val parentFingerprint: ParentFingerprint? = null,
)

/** Keeps the existing desktop.properties format, including unrecognised properties. */
internal class DesktopSettings(
    private val path: Path = Path.of(System.getProperty("user.home"), ".enderlink", "desktop.properties"),
) {
    private val properties = Properties().apply {
        runCatching { Files.newInputStream(path).use(::load) }
    }

    fun load(): DesktopPreferences = DesktopPreferences(
        repository = properties.getProperty("repository", ""),
        saves = properties.getProperty("saves", MinecraftSaves.defaultDirectory().toString()),
        saveName = properties.getProperty("saveName", "EnderLink World"),
        serverBase = properties.getProperty("serverBase", ""),
        jar = properties.getProperty("jar", ""),
        parentFingerprint = runCatching {
            ParentFingerprint(
                properties.getProperty("parentVersion"),
                properties.getProperty("parentFormat").toInt(),
                properties.getProperty("parentHash"),
            )
        }.getOrNull(),
    )

    fun save(preferences: DesktopPreferences) {
        properties.setProperty("repository", preferences.repository.trim())
        properties.setProperty("saves", preferences.saves.trim())
        properties.setProperty("saveName", preferences.saveName.trim())
        properties.setProperty("serverBase", preferences.serverBase.trim())
        properties.setProperty("jar", preferences.jar.trim())
        preferences.parentFingerprint?.let {
            properties.setProperty("parentVersion", it.versionId)
            properties.setProperty("parentFormat", it.formatVersion.toString())
            properties.setProperty("parentHash", it.contentHash)
        } ?: run {
            properties.remove("parentVersion"); properties.remove("parentFormat"); properties.remove("parentHash")
        }
        runCatching {
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { properties.store(it, "EnderLink Desktop paths") }
        }
    }
}
