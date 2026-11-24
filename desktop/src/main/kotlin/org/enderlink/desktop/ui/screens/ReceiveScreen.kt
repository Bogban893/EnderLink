package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.Surface
import org.enderlink.desktop.ui.components.TransferBar
import org.enderlink.desktop.ui.components.combo
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import org.enderlink.desktop.ui.components.routeGroup
import org.enderlink.desktop.ui.components.routePanel
import org.enderlink.desktop.ui.components.transferProgress
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField

internal class ReceiveScreen(
    onChooseDirectory: (JTextField) -> Unit,
    onReceive: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val friendReceive = combo(arrayOf(""))
    val receivePort = field("25570")
    val receiveRoute = routeGroup()
    val savesField = field()
    val saveNameField = field()
    val ownerField = field()
    val receiveProgress = TransferBar()
    val receiveProgressText = label("", 12, C.muted)
    val actionButtons = mutableListOf<JButton>()
    val cancelButtons = mutableListOf<BlockButton>()

    val panel: JPanel = page(messages.text("receive.title"), messages.text("receive.description")).apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("receive.safetySection"), 11, C.green, true)); add(gap(11))
            add(label(messages.text("receive.verifiedHint"), 15, C.text, true)); add(gap(8))
            add(label(messages.text("receive.conflictHint"), 12, C.muted))
        }
        add(info); add(gap(20))
        add(routePanel(receiveRoute, friendReceive, null, receivePort, false)); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("receive.installSection"), 11, C.green, true)); add(gap(12))
            add(label(messages.text("receive.savesLabel"), 13, C.text, true)); add(gap(7))
            add(horizontal(savesField, BlockButton(messages.text("action.select")).apply { addActionListener { onChooseDirectory(savesField) } }))
            add(gap(12)); add(label(messages.text("receive.folderLabel"), 13, C.text, true)); add(gap(7)); add(saveNameField)
            add(gap(12)); add(label(messages.text("player.ownerUuidLabel"), 13, C.text, true)); add(gap(7)); add(ownerField)
            add(gap(10)); add(label(messages.text("receive.installHint"), 12, C.muted))
            add(gap(6)); add(label(messages.text("receive.versionHint"), 12, C.gold))
        }); add(gap(20))
        add(transferProgress(receiveProgress, receiveProgressText))
        add(gap(20))
        add(horizontal(BlockButton(messages.text("action.waitWorld"), true).also { actionButtons += it }.apply {
            addActionListener { onReceive() }
        }, BlockButton(messages.text("action.cancel")).also { cancelButtons += it }.apply {
            addActionListener { onCancel() }
        }, BlockButton(messages.text("action.install")).also { actionButtons += it }.apply {
            addActionListener { onInstall() }
        }))
    }
}
