package org.enderlink.desktop.ui.dialogs

import com.formdev.flatlaf.util.SystemFileChooser
import org.enderlink.desktop.i18n.messages
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs

class DesktopDialogsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `folder selection uses the current directory and returns a path with spaces and Cyrillic`() = onEdt {
        val current = Files.createDirectory(root.resolve("Исходный мир"))
        val selected = Files.createDirectory(root.resolve("Новая папка"))
        val chooser = TestChooser(SystemFileChooser.APPROVE_OPTION, selected.toFile())
        val parent = JPanel()
        val dialogs = DesktopDialogs(parent) { chooser }

        assertEquals(selected.toFile().absolutePath, dialogs.chooseDirectory(current.toString()))
        assertEquals(current.toFile(), chooser.openedDirectory)
        assertEquals(SystemFileChooser.DIRECTORIES_ONLY, chooser.fileSelectionMode)
        assertFalse(chooser.isMultiSelectionEnabled)
        assertSame(parent, chooser.openedParent)
    }

    @Test
    fun `cancelling folder or jar selection returns no replacement path`() = onEdt {
        val chooser = TestChooser(SystemFileChooser.CANCEL_OPTION)
        val dialogs = DesktopDialogs(JPanel()) { chooser }
        assertNull(dialogs.chooseDirectory(""))
        assertNull(dialogs.chooseDirectory("invalid\u0000path"))
        assertNull(dialogs.chooseJar())
    }

    @Test
    fun `jar selection keeps the jar filter and single-file selection`() = onEdt {
        val jar = Files.createFile(root.resolve("сервер Minecraft.jar"))
        val chooser = TestChooser(SystemFileChooser.APPROVE_OPTION, jar.toFile())
        val dialogs = DesktopDialogs(JPanel()) { chooser }

        assertEquals(jar.toFile().absolutePath, dialogs.chooseJar())
        assertEquals(SystemFileChooser.FILES_ONLY, chooser.fileSelectionMode)
        assertFalse(chooser.isMultiSelectionEnabled)
        val filter = assertIs<SystemFileChooser.FileNameExtensionFilter>(chooser.fileFilter)
        assertEquals(messages.text("fileChooser.jarFilter"), filter.description)
        assertContentEquals(arrayOf("jar"), filter.extensions)
    }

    @Test
    fun `chooser opens nearest existing parent for typed paths`() = onEdt {
        val folder = Files.createDirectory(root.resolve("папка с пробелами"))
        val chooser = TestChooser(SystemFileChooser.CANCEL_OPTION)
        val dialogs = DesktopDialogs(JPanel()) { chooser }

        dialogs.chooseDirectory(folder.resolve("new/world").toString())
        assertEquals(folder.toFile(), chooser.openedDirectory)
        dialogs.chooseJar(folder.resolve("server.jar").toString())
        assertEquals(folder.toFile(), chooser.openedDirectory)
    }

    private class TestChooser(private val result: Int, private val selection: File? = null) : SystemFileChooser() {
        var openedParent: Component? = null
        var openedDirectory: File? = null

        override fun showOpenDialog(parent: Component): Int {
            openedParent = parent
            openedDirectory = currentDirectory
            selectedFile = selection
            return result
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
