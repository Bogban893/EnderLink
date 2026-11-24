package org.enderlink.desktop.ui.dialogs

import org.enderlink.desktop.ui.shortId
import java.awt.Component
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

internal class DesktopDialogs(private val parent: Component) {
    fun warning(text: String) {
        JOptionPane.showMessageDialog(parent, text, "EnderLink", JOptionPane.WARNING_MESSAGE)
    }

    fun error(text: String) {
        JOptionPane.showMessageDialog(parent, text, "EnderLink", JOptionPane.ERROR_MESSAGE)
    }

    fun confirmServerStop(): Boolean = JOptionPane.showConfirmDialog(parent,
        "Сохранить мир и остановить сервер перед выходом?", "Сервер работает", JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    fun confirmIncomingVersion(local: String?, incoming: String): Boolean = JOptionPane.showConfirmDialog(parent,
        "Входящая версия ${shortId(incoming)} сохранена.\n" +
            "Активная версия ${local?.let(::shortId) ?: "отсутствует"}.\n\n" +
            "Переключиться на входящую ветку?", "Конфликт версий", JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    fun chooseDirectory(current: String): String? {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            current.takeIf(String::isNotBlank)?.let { raw ->
                runCatching { Path.of(raw).toFile() }.getOrNull()?.let { currentDirectory = it }
            }
        }
        return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    fun chooseJar(): String? {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("Java Archive (*.jar)", "jar")
        }
        return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
