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

internal class SendScreen(
    onChooseDirectory: (JTextField) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSelectInstalled: () -> Unit = {},
) {
    val sourceField = field()
    val ownerField = field()
    val friendSend = combo(arrayOf(""))
    val sendHost = field("127.0.0.1")
    val sendPort = field("25570")
    val sendRoute = routeGroup()
    val sendProgress = TransferBar()
    val sendProgressText = label("", 12, C.muted)
    val actionButtons = mutableListOf<JButton>()
    val cancelButtons = mutableListOf<BlockButton>()

    val panel: JPanel = page(messages.text("send.title"), messages.text("send.description")).apply {
        val world = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("send.sourceSection"), 11, C.green, true)); add(gap(15))
            add(label(messages.text("send.sourceLabel"), 13, C.text, true)); add(gap(7))
            add(horizontal(sourceField, BlockButton(messages.text("action.select")).apply { addActionListener { onChooseDirectory(sourceField) } }))
            add(gap(10)); add(label(messages.text("send.existingHint"), 12, C.muted))
            add(gap(10)); add(BlockButton(messages.text("action.selectInstalled")).also { actionButtons += it }.apply {
                addActionListener { onSelectInstalled() }
            })
            add(gap(8)); add(label(messages.text("send.installedHint"), 12, C.muted))
            add(gap(12)); add(label(messages.text("send.closedHint"), 12, C.gold))
            add(gap(12)); add(label(messages.text("player.ownerUuidLabel"), 13, C.text, true)); add(gap(7)); add(ownerField)
        }
        add(world); add(gap(20))
        add(routePanel(sendRoute, friendSend, sendHost, sendPort, true)); add(gap(20))
        add(transferProgress(sendProgress, sendProgressText))
        add(gap(20))
        add(horizontal(BlockButton(messages.text("action.sendWorld"), true).also { actionButtons += it }.apply {
            addActionListener { onSend() }
        }, BlockButton(messages.text("action.cancel")).also { cancelButtons += it }.apply { addActionListener { onCancel() } }))
    }
}
