package org.enderlink.desktop.presentation

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DesktopPathSelectionTest {
    @TempDir lateinit var root: Path

    @Test
    fun `source must contain readable world metadata and remain outside library`() {
        val library = Files.createDirectory(root.resolve("library"))
        val saves = Files.createDirectory(root.resolve("launcher saves"))
        val world = Files.createDirectory(saves.resolve("Мир с пробелом"))
        assertFailsWith<IOException> { DesktopPathSelection.directory(DirectoryPurpose.SOURCE, world, library, saves, null) }
        Files.createFile(world.resolve("level.dat"))
        DesktopPathSelection.directory(DirectoryPurpose.SOURCE, world, library, saves, null)
        assertFailsWith<IOException> { DesktopPathSelection.directory(DirectoryPurpose.SAVES, world, library, saves, null) }
        assertFailsWith<IOException> { DesktopPathSelection.directory(DirectoryPurpose.SOURCE, world, saves, saves, null) }
    }

    @Test
    fun `saves cannot overlap library or repository`() {
        val library = Files.createDirectory(root.resolve("library"))
        val repository = Files.createDirectory(library.resolve("repo"))
        val saves = Files.createDirectory(root.resolve("launcher saves"))
        DesktopPathSelection.directory(DirectoryPurpose.SAVES, saves, library, saves, repository)
        assertFailsWith<IOException> { DesktopPathSelection.directory(DirectoryPurpose.SAVES, library, library, saves, repository) }
        assertFailsWith<IOException> { DesktopPathSelection.directory(DirectoryPurpose.REPOSITORY, saves, library, saves, repository) }
    }

    @Test
    fun `jar must be a readable regular jar file`() {
        val jar = Files.createFile(root.resolve("сервер Minecraft.jar"))
        DesktopPathSelection.jar(jar)
        assertFailsWith<IOException> { DesktopPathSelection.jar(root) }
        assertFailsWith<IOException> { DesktopPathSelection.jar(Files.createFile(root.resolve("server.txt"))) }
    }
}
