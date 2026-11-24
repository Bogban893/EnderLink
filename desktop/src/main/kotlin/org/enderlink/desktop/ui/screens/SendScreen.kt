package org.enderlink.desktop.ui.screens

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
) {
    val sourceField = field()
    val friendSend = combo(arrayOf(""))
    val sendHost = field("127.0.0.1")
    val sendPort = field("25570")
    val sendRoute = routeGroup()
    val sendProgress = TransferBar()
    val sendProgressText = label("", 12, C.muted)
    val actionButtons = mutableListOf<JButton>()
    val cancelButtons = mutableListOf<BlockButton>()

    val panel: JPanel = page("ОТПРАВИТЬ МИР", "Сначала создадим проверенную версию, затем передадим только нужные блоки.").apply {
        val world = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("01  ИСТОЧНИК", 11, C.green, true)); add(gap(15))
            add(label("Папка закрытого мира", 13, C.text, true)); add(gap(7))
            add(horizontal(sourceField, BlockButton("Выбрать…").apply { addActionListener { onChooseDirectory(sourceField) } }))
            add(gap(10)); add(label("Оставьте поле пустым, чтобы отправить уже опубликованную версию.", 12, C.muted))
            add(gap(12)); add(label("В Minecraft мир должен быть закрыт до начала передачи.", 12, C.gold))
        }
        add(world); add(gap(20))
        add(routePanel(sendRoute, friendSend, sendHost, sendPort, true)); add(gap(20))
        add(transferProgress(sendProgress, sendProgressText))
        add(gap(20))
        add(horizontal(BlockButton("Отправить мир  ➜", true).also { actionButtons += it }.apply {
            addActionListener { onSend() }
        }, BlockButton("Отменить").also { cancelButtons += it }.apply { addActionListener { onCancel() } }))
    }
}
