package org.enderlink.desktop.ui.components

import org.enderlink.desktop.i18n.messages
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

internal fun routePanel(group: Pair<JRadioButton, JRadioButton>, friend: javax.swing.JComboBox<String>,
                       host: javax.swing.JTextField?, port: javax.swing.JTextField, sending: Boolean): JPanel = Surface().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    add(label(messages.text("connection.section"), 11, C.green, true)); add(gap(12))
    add(horizontal(group.first, group.second)); add(gap(16))
    add(label(if (sending) messages.text("connection.recipient") else messages.text("connection.sender"), 13, C.text, true)); add(gap(7)); add(friend)
    add(gap(13))
    if (host != null) { add(label(messages.text("connection.address"), 13, C.text, true)); add(gap(7)); add(host); add(gap(13)) }
    add(label(messages.text("connection.port"), 13, C.text, true)); add(gap(7)); add(port)
    fun update() {
        friend.isEnabled = group.first.isSelected
        friend.background = if (group.first.isSelected) C.input else C.panelLight
        for (input in listOfNotNull(host, port)) {
            input.isEnabled = group.second.isSelected
            input.background = if (group.second.isSelected) C.input else C.panelLight
            input.foreground = if (group.second.isSelected) C.text else C.muted
        }
    }
    group.first.addActionListener { update() }
    group.second.addActionListener { update() }
    update()
}

internal fun routeGroup(): Pair<JRadioButton, JRadioButton> {
    val first = JRadioButton(messages.text("connection.friendRoute"), true)
    val second = JRadioButton(messages.text("connection.localRoute"))
    ButtonGroup().apply { add(first); add(second) }
    for (button in listOf(first, second)) {
        button.font = font(13, true); button.foreground = C.text; button.background = C.panel
        button.isFocusPainted = false
        button.icon = PixelRadioIcon(false)
        button.selectedIcon = PixelRadioIcon(true)
        button.iconTextGap = 9
    }
    return first to second
}

internal fun transferProgress(bar: TransferBar, text: JLabel): JPanel = Surface(C.panelLight).apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    add(label(messages.text("transfer.progressSection"), 11, C.green, true)); add(gap(12))
    add(bar); add(gap(8)); add(text)
}
