package org.enderlink.desktop.ui.dialogs

import com.formdev.flatlaf.util.SystemFileChooser
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter
import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.shortId
import java.awt.Component
import java.nio.file.Path
import javax.swing.JOptionPane

internal class DesktopDialogs(
    private val parent: Component,
    private val createFileChooser: () -> SystemFileChooser = { SystemFileChooser() },
) {
    fun warning(text: String) {
        JOptionPane.showMessageDialog(parent, text, messages.text("app.name"), JOptionPane.WARNING_MESSAGE)
    }

    fun error(text: String) {
        JOptionPane.showMessageDialog(parent, text, messages.text("app.name"), JOptionPane.ERROR_MESSAGE)
    }

    fun confirmServerStop(): Boolean = JOptionPane.showConfirmDialog(parent,
        messages.text("server.confirmStop"), messages.text("server.runningTitle"), JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    fun confirmIncomingVersion(local: String?, incoming: String): Boolean = JOptionPane.showConfirmDialog(parent,
        messages.text("conflict.confirmIncoming", shortId(incoming),
            local?.let(::shortId) ?: messages.text("conflict.noActiveVersion")),
        messages.text("conflict.title"), JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    fun chooseDirectory(current: String): String? {
        val chooser = createFileChooser().apply {
            fileSelectionMode = SystemFileChooser.DIRECTORIES_ONLY
            current.takeIf(String::isNotBlank)?.let { raw ->
                runCatching { Path.of(raw).toFile() }.getOrNull()?.let { currentDirectory = it }
            }
        }
        return if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    fun chooseJar(): String? {
        val chooser = createFileChooser().apply {
            fileFilter = FileNameExtensionFilter(messages.text("fileChooser.jarFilter"), "jar")
        }
        return if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
