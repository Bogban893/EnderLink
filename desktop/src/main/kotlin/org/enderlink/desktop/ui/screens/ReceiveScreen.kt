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
    val receiveProgress = TransferBar()
    val receiveProgressText = label("", 12, C.muted)
    val actionButtons = mutableListOf<JButton>()
    val cancelButtons = mutableListOf<BlockButton>()

    val panel: JPanel = page("ПРИНЯТЬ МИР", "Открой ожидание, а друг выберет тебя на экране отправки.").apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("БЕЗОПАСНОЕ ПОЛУЧЕНИЕ", 11, C.green, true)); add(gap(11))
            add(label("Версия активируется после проверки всех блоков.", 15, C.text, true)); add(gap(8))
            add(label("При конфликте входящая версия сохранится, а выбор ветки останется за вами.", 12, C.muted))
        }
        add(info); add(gap(20))
        add(routePanel(receiveRoute, friendReceive, null, receivePort, false)); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("03  СОХРАНЕНИЕ В MINECRAFT", 11, C.green, true)); add(gap(12))
            add(label("Папка saves вашей установки Minecraft Java", 13, C.text, true)); add(gap(7))
            add(horizontal(savesField, BlockButton("Выбрать…").apply { addActionListener { onChooseDirectory(savesField) } }))
            add(gap(12)); add(label("Имя папки мира", 13, C.text, true)); add(gap(7)); add(saveNameField)
            add(gap(10)); add(label("Мир появится в списке одиночной игры после получения. Закройте его перед обновлением.", 12, C.muted))
            add(gap(6)); add(label("Открывайте полученный мир совместимой версией Minecraft Java.", 12, C.gold))
        }); add(gap(20))
        add(transferProgress(receiveProgress, receiveProgressText))
        add(gap(20))
        add(horizontal(BlockButton("Ждать мир  ⇩", true).also { actionButtons += it }.apply {
            addActionListener { onReceive() }
        }, BlockButton("Отменить").also { cancelButtons += it }.apply {
            addActionListener { onCancel() }
        }, BlockButton("Обновить Minecraft").also { actionButtons += it }.apply {
            addActionListener { onInstall() }
        }))
    }
}
