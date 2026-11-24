package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.Surface
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField

internal class ServerScreen(
    onChooseDirectory: (JTextField) -> Unit,
    onChooseJar: (JTextField) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val serverBaseField = field()
    val jarField = field()
    val serverStatus = label(messages.text("server.notRunning"), 15, C.muted)
    val stopButton = BlockButton(messages.text("action.stopServer"), true)
    val actionButtons = mutableListOf<JButton>()

    val panel: JPanel = page(messages.text("server.title"), messages.text("server.description")).apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("server.statusSection"), 11, C.green, true)); add(gap(11)); add(serverStatus)
            add(gap(9)); add(label(messages.text("server.publishHint"), 12, C.muted))
        }
        add(info); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("server.settingsSection"), 11, C.green, true)); add(gap(14))
            add(label(messages.text("server.directoryLabel"), 13, C.text, true)); add(gap(7))
            add(horizontal(serverBaseField, BlockButton(messages.text("action.select")).apply { addActionListener { onChooseDirectory(serverBaseField) } }))
            add(gap(14)); add(label(messages.text("server.jarLabel"), 13, C.text, true)); add(gap(7))
            add(horizontal(jarField, BlockButton(messages.text("action.select")).apply { addActionListener { onChooseJar(jarField) } }))
            add(gap(12)); add(label(messages.text("server.sessionHint"), 12, C.muted))
            add(gap(7)); add(label(messages.text("server.propertiesHint"), 12, C.gold))
        }); add(gap(20))
        add(horizontal(BlockButton(messages.text("action.startServer"), true).also { actionButtons += it }.apply {
            addActionListener { onStart() }
        }, stopButton.apply { addActionListener { onStop() } }))
    }
}
