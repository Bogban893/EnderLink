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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal class FriendsScreen(
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val ownId = field()
    val friendName = field()
    val friendId = field()
    val friendsList = label("Загрузка друзей…", 14, C.muted)
    val actionButtons = mutableListOf<JButton>()

    val panel: JPanel = page("ДРУЗЬЯ", "Обменяйтесь ID один раз, затем выбирайте имя при передаче.").apply {
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ВАШ ID", 11, C.green, true)); add(gap(10)); ownId.isEditable = false
            add(horizontal(ownId, BlockButton("Копировать").apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ownId.text), null)
                    onStatus("ID скопирован")
                }
            }))
            add(gap(9)); add(label("Отправьте ID другу. Он тоже должен добавить ваш ID у себя.", 12, C.muted))
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ДОБАВИТЬ ДРУГА", 11, C.green, true)); add(gap(13))
            add(label("Имя", 13, C.text, true)); add(gap(6)); add(friendName); add(gap(12))
            add(label("ID друга", 13, C.text, true)); add(gap(6)); add(friendId); add(gap(17))
            add(BlockButton("Добавить друга", true).also { actionButtons += it }.apply { addActionListener { onAdd() } })
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("СОХРАНЁННЫЕ ДРУЗЬЯ", 11, C.green, true)); add(gap(11)); add(friendsList)
            add(gap(15)); add(BlockButton("Обновить список").apply { addActionListener { onRefresh() } })
        })
    }
}
