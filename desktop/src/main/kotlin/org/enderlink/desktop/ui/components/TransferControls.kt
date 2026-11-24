package org.enderlink.desktop.ui.components

import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

internal fun routePanel(group: Pair<JRadioButton, JRadioButton>, friend: javax.swing.JComboBox<String>,
                       host: javax.swing.JTextField?, port: javax.swing.JTextField, sending: Boolean): JPanel = Surface().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    add(label("02  ПОДКЛЮЧЕНИЕ", 11, C.green, true)); add(gap(12))
    add(horizontal(group.first, group.second)); add(gap(16))
    add(label(if (sending) "Кому отправить" else "От кого принять", 13, C.text, true)); add(gap(7)); add(friend)
    add(gap(13))
    if (host != null) { add(label("Адрес в локальной сети", 13, C.text, true)); add(gap(7)); add(host); add(gap(13)) }
    add(label("TCP-порт", 13, C.text, true)); add(gap(7)); add(port)
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
    val first = JRadioButton("Через интернет · друг", true)
    val second = JRadioButton("Локальная сеть · TCP")
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
    add(label("ХОД ПЕРЕДАЧИ", 11, C.green, true)); add(gap(12))
    add(bar); add(gap(8)); add(text)
}
