package org.enderlink.desktop.settings

import org.enderlink.worldtransfer.ParentFingerprint
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopSettingsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `existing settings keep paths parent fingerprint and unknown properties after saving`() {
        val path = root.resolve("desktop.properties")
        val parent = ParentFingerprint("sha256:" + "a".repeat(64), 2, "sha256:" + "b".repeat(64))
        val existing = Properties().apply {
            setProperty("repository", root.resolve("Мои миры").toString())
            setProperty("saves", root.resolve("Minecraft saves").toString())
            setProperty("saveName", "Мир друзей")
            setProperty("serverBase", root.resolve("server").toString())
            setProperty("jar", root.resolve("server.jar").toString())
            setProperty("parentVersion", parent.versionId)
            setProperty("parentFormat", parent.formatVersion.toString())
            setProperty("parentHash", parent.contentHash)
            setProperty("extra", "keep")
        }
        Files.newOutputStream(path).use { existing.store(it, null) }
        val settings = DesktopSettings(path)
        val preferences = settings.load()
        assertEquals(existing.getProperty("repository"), preferences.repository)
        assertEquals(existing.getProperty("saves"), preferences.saves)
        assertEquals(existing.getProperty("serverBase"), preferences.serverBase)
        assertEquals(existing.getProperty("jar"), preferences.jar)
        assertEquals(parent, preferences.parentFingerprint)

        settings.save(preferences.copy(saveName = " Новое имя "))
        assertEquals(preferences.copy(saveName = "Новое имя"), DesktopSettings(path).load())
        val stored = Properties().apply { Files.newInputStream(path).use(::load) }
        assertEquals("keep", stored.getProperty("extra"))

        settings.save(preferences.copy(parentFingerprint = null))
        val cleared = Properties().apply { Files.newInputStream(path).use(::load) }
        assertFalse(cleared.containsKey("parentVersion"))
        assertFalse(cleared.containsKey("parentFormat"))
        assertFalse(cleared.containsKey("parentHash"))
        assertNull(DesktopSettings(path).load().parentFingerprint)
    }

    @Test
    fun `missing or invalid parent metadata does not prevent loading paths`() {
        val path = root.resolve("nested/desktop.properties")
        val settings = DesktopSettings(path)
        assertEquals(DesktopPreferences(), settings.load())
        assertFalse(Files.exists(path))
        settings.save(DesktopPreferences(repository = " repo ", saveName = " Мир "))
        assertEquals("repo", DesktopSettings(path).load().repository)
        assertEquals("Мир", DesktopSettings(path).load().saveName)

        Files.writeString(path, "repository=repo\nparentFormat=invalid\nparentVersion=version\n")
        val loaded = DesktopSettings(path).load()
        assertEquals("repo", loaded.repository)
        assertNull(loaded.parentFingerprint)
    }

    @Test
    fun `world library paths survive restart and can be removed`() {
        val path = root.resolve("desktop.properties")
        val first = root.resolve("world A").toString()
        val second = root.resolve("world B").toString()
        DesktopSettings(path).save(DesktopPreferences(libraryRoot = root.resolve("managed worlds").toString(),
            knownRepositories = listOf(first, second, first)))
        assertEquals(listOf(first, second), DesktopSettings(path).load().knownRepositories)
        assertEquals(root.resolve("managed worlds").toString(), DesktopSettings(path).load().libraryRoot)
        DesktopSettings(path).save(DesktopSettings(path).load().copy(knownRepositories = listOf(second)))
        assertEquals(listOf(second), DesktopSettings(path).load().knownRepositories)
    }

    @Test
    fun `coordinator address survives restart`() {
        val path = root.resolve("desktop.properties")
        DesktopSettings(path).save(DesktopPreferences(coordinatorUrl = " https://discovery.example "))
        assertEquals("https://discovery.example", DesktopSettings(path).load().coordinatorUrl)
    }

    @Test
    fun `LAN nickname is opt in and retains exact case after restart`() {
        val path = root.resolve("desktop.properties")
        assertEquals("", DesktopSettings(path).load().lanPlayerName)
        DesktopSettings(path).save(DesktopPreferences(lanPlayerName = " PlayerA "))
        assertEquals("PlayerA", DesktopSettings(path).load().lanPlayerName)
        DesktopSettings(path).save(DesktopSettings(path).load().copy(lanPlayerName = ""))
        assertEquals("", DesktopSettings(path).load().lanPlayerName)
    }

    @Test
    fun `each repository retains its own minecraft folder and parent fingerprint`() {
        val path = root.resolve("desktop.properties")
        val first = root.resolve("first").toString()
        val second = root.resolve("second").toString()
        val parent = ParentFingerprint("sha256:" + "a".repeat(64), 2, "sha256:" + "b".repeat(64))
        val states = mapOf(first to WorldUiState("Первый мир", parent), second to WorldUiState("Второй мир", null))
        DesktopSettings(path).save(DesktopPreferences(repository = first, worldStates = states))
        assertEquals(states, DesktopSettings(path).load().worldStates)
    }

    @Test
    fun `chooser folders are saved independently and unknown keys are ignored`() {
        val path = root.resolve("desktop.properties")
        val folders = mapOf("source" to root.resolve("Мир с пробелом").toString(),
            "saves" to root.resolve("другой launcher/saves").toString(), "jar" to root.toString())
        DesktopSettings(path).save(DesktopPreferences(chooserDirectories = folders + ("unknown" to "ignored")))
        assertEquals(folders, DesktopSettings(path).load().chooserDirectories)
        DesktopSettings(path).save(DesktopSettings(path).load().copy(chooserDirectories = folders - "source"))
        assertEquals(folders - "source", DesktopSettings(path).load().chooserDirectories)
    }
}
