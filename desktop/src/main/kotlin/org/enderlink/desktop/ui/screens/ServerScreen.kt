package org.enderlink.desktop.ui.screens

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
    val serverStatus = label("Сервер не запущен", 15, C.muted)
    val stopButton = BlockButton("Сохранить и остановить", true)
    val actionButtons = mutableListOf<JButton>()

    val panel: JPanel = page("СЕРВЕР", "Запусти опубликованный мир на своём компьютере.").apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("СОСТОЯНИЕ", 11, C.green, true)); add(gap(11)); add(serverStatus)
            add(gap(9)); add(label("После остановки мир автоматически публикуется как новая версия.", 12, C.muted))
        }
        add(info); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("НАСТРОЙКИ VANILLA СЕРВЕРА", 11, C.green, true)); add(gap(14))
            add(label("Папка с eula.txt и server.properties", 13, C.text, true)); add(gap(7))
            add(horizontal(serverBaseField, BlockButton("Выбрать…").apply { addActionListener { onChooseDirectory(serverBaseField) } }))
            add(gap(14)); add(label("Файл server.jar", 13, C.text, true)); add(gap(7))
            add(horizontal(jarField, BlockButton("Выбрать…").apply { addActionListener { onChooseJar(jarField) } }))
            add(gap(12)); add(label("Для каждого запуска создаётся отдельная папка с копией мира и журналом.", 12, C.muted))
            add(gap(7)); add(label("В server.properties используйте online-mode=true и level-name=world.", 12, C.gold))
        }); add(gap(20))
        add(horizontal(BlockButton("Запустить сервер", true).also { actionButtons += it }.apply {
            addActionListener { onStart() }
        }, stopButton.apply { addActionListener { onStop() } }))
    }
}
