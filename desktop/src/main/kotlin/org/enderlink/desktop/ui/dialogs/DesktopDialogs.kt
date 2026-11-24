package org.enderlink.desktop.ui.dialogs

import com.formdev.flatlaf.util.SystemFileChooser
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter
import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.shortId
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.Timer
import javax.swing.JDialog

internal class DesktopDialogs(
    private val parent: Component,
    private val createFileChooser: () -> SystemFileChooser = { SystemFileChooser() },
) {
    private var incomingDialog: JDialog? = null

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

    fun confirmIncomingWorld(peer: String, worldId: String, versionId: String, fileCount: Int, bytes: String): Boolean {
        val prompt = messages.text("transfer.incomingConfirm", peer, shortId(worldId), shortId(versionId),
            fileCount.toString(), bytes)
        val no = messages.text("dialog.no")
        val yes = messages.text("dialog.yes")
        val pane = JOptionPane(prompt, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION,
            null, arrayOf(no, yes), no)
        val dialog = pane.createDialog(parent, messages.text("transfer.incomingTitle"))
        val timeout = Timer(55_000) { dialog.dispose() }.apply { isRepeats = false }
        return try {
            incomingDialog = dialog
            timeout.start()
            dialog.isVisible = true
            pane.value == yes
        } finally {
            timeout.stop()
            incomingDialog = null
            dialog.dispose()
        }
    }

    fun dismissIncomingWorld() { incomingDialog?.dispose() }

    fun confirmLibraryMove(from: String, to: String): Boolean = JOptionPane.showConfirmDialog(parent,
        messages.text("library.confirmMove", from, to), messages.text("library.moveTitle"), JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    fun chooseDirectory(current: String): String? {
        val chooser = createFileChooser().apply {
            fileSelectionMode = SystemFileChooser.DIRECTORIES_ONLY
            startingDirectory(current)?.let { currentDirectory = it.toFile() }
        }
        return if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    fun chooseJar(current: String = ""): String? {
        val chooser = createFileChooser().apply {
            fileFilter = FileNameExtensionFilter(messages.text("fileChooser.jarFilter"), "jar")
            startingDirectory(current)?.let { currentDirectory = it.toFile() }
        }
        return if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    private fun startingDirectory(raw: String): Path? {
        if (raw.isBlank()) return null
        var path = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: return null
        while (!Files.isDirectory(path)) {
            path = path.parent ?: return null
        }
        return path
    }
}
