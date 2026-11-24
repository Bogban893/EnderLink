package org.enderlink.desktop.settings

import org.enderlink.desktop.minecraft.MinecraftSaves
import org.enderlink.worldtransfer.ParentFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal data class DesktopPreferences(
    val repository: String = "",
    val libraryRoot: String = DesktopWorldLibrary.defaultDirectory().toString(),
    val saves: String = MinecraftSaves.defaultDirectory().toString(),
    val saveName: String = "EnderLink World",
    val ownerUuid: String = "",
    val coordinatorUrl: String = "",
    val serverBase: String = "",
    val jar: String = "",
    val parentFingerprint: ParentFingerprint? = null,
    val knownRepositories: List<String> = emptyList(),
    val worldStates: Map<String, WorldUiState> = emptyMap(),
    val chooserDirectories: Map<String, String> = emptyMap(),
)

internal data class WorldUiState(val saveName: String, val parentFingerprint: ParentFingerprint?)

/** Keeps the existing desktop.properties format, including unrecognised properties. */
internal class DesktopSettings(
    private val path: Path = Path.of(System.getProperty("user.home"), ".enderlink", "desktop.properties"),
) {
    private val properties = Properties().apply {
        runCatching { Files.newInputStream(path).use(::load) }
    }

    fun load(): DesktopPreferences = DesktopPreferences(
        repository = properties.getProperty("repository", ""),
        libraryRoot = properties.getProperty("libraryRoot")?.takeIf(String::isNotBlank)
            ?: DesktopWorldLibrary.defaultDirectory().toString(),
        saves = properties.getProperty("saves", MinecraftSaves.defaultDirectory().toString()),
        saveName = properties.getProperty("saveName", "EnderLink World"),
        ownerUuid = properties.getProperty("ownerUuid", ""),
        coordinatorUrl = properties.getProperty("coordinatorUrl", ""),
        serverBase = properties.getProperty("serverBase", ""),
        jar = properties.getProperty("jar", ""),
        parentFingerprint = runCatching {
            ParentFingerprint(
                properties.getProperty("parentVersion"),
                properties.getProperty("parentFormat").toInt(),
                properties.getProperty("parentHash"),
            )
        }.getOrNull(),
        knownRepositories = (0 until (properties.getProperty("knownRepositoryCount")?.toIntOrNull() ?: 0).coerceIn(0, 100))
            .mapNotNull { properties.getProperty("knownRepository.$it")?.takeIf(String::isNotBlank) },
        worldStates = (0 until (properties.getProperty("worldStateCount")?.toIntOrNull() ?: 0).coerceIn(0, 100))
            .mapNotNull { index ->
                val key = "worldState.$index"
                val repository = properties.getProperty("$key.repository")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val saveName = properties.getProperty("$key.saveName") ?: return@mapNotNull null
                val parent = runCatching {
                    ParentFingerprint(properties.getProperty("$key.parentVersion"),
                        properties.getProperty("$key.parentFormat").toInt(), properties.getProperty("$key.parentHash"))
                }.getOrNull()
                repository to WorldUiState(saveName, parent)
            }.toMap(),
        chooserDirectories = chooserKeys.mapNotNull { key ->
            properties.getProperty("chooser.$key")?.takeIf(String::isNotBlank)?.let { key to it }
        }.toMap(),
    )

    fun save(preferences: DesktopPreferences) {
        properties.setProperty("repository", preferences.repository.trim())
        properties.setProperty("libraryRoot", preferences.libraryRoot.trim())
        properties.setProperty("saves", preferences.saves.trim())
        properties.setProperty("saveName", preferences.saveName.trim())
        properties.setProperty("ownerUuid", preferences.ownerUuid.trim())
        properties.setProperty("coordinatorUrl", preferences.coordinatorUrl.trim())
        properties.setProperty("serverBase", preferences.serverBase.trim())
        properties.setProperty("jar", preferences.jar.trim())
        chooserKeys.forEach { key ->
            val directory = preferences.chooserDirectories[key]?.trim()
            if (directory.isNullOrBlank()) properties.remove("chooser.$key")
            else properties.setProperty("chooser.$key", directory)
        }
        properties.stringPropertyNames().filter { it.startsWith("knownRepository.") || it == "knownRepositoryCount" }
            .forEach(properties::remove)
        val repositories = preferences.knownRepositories.distinct().take(100)
        properties.setProperty("knownRepositoryCount", repositories.size.toString())
        repositories.forEachIndexed { index, path -> properties.setProperty("knownRepository.$index", path) }
        properties.stringPropertyNames().filter { it.startsWith("worldState.") || it == "worldStateCount" }
            .forEach(properties::remove)
        val states = preferences.worldStates.entries.take(100)
        properties.setProperty("worldStateCount", states.size.toString())
        states.forEachIndexed { index, (repository, state) ->
            val key = "worldState.$index"
            properties.setProperty("$key.repository", repository)
            properties.setProperty("$key.saveName", state.saveName)
            state.parentFingerprint?.let {
                properties.setProperty("$key.parentVersion", it.versionId)
                properties.setProperty("$key.parentFormat", it.formatVersion.toString())
                properties.setProperty("$key.parentHash", it.contentHash)
            }
        }
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

    private companion object {
        val chooserKeys = listOf("repository", "source", "saves", "server", "library", "jar")
    }
}
