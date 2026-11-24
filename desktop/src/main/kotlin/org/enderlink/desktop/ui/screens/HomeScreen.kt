package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.PixelScene
import org.enderlink.desktop.ui.components.Surface
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class HomeScreen(
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenWorld: () -> Unit,
    onImportOwner: () -> Unit,
) {
    val ownerField = field()
    val statusWorld = label("Мир ещё не выбран", 19, C.text, true)
    val statusDetail = label("Выберите хранилище версий выше", 13, C.muted)
    val playerHint = label("", 13, C.gold)
    val actionButtons = mutableListOf<JButton>()

    val panel: JPanel = page("ОБЗОР", "Всё нужное для передачи мира — в одном месте.").apply {
        add(PixelScene().apply { alignmentX = 0f })
        add(gap(16))
        add(horizontal(BlockButton("Отправить мир", true).apply { addActionListener { onNavigate("send") } },
            BlockButton("Принять мир").apply { addActionListener { onNavigate("receive") } }))
        add(gap(23))
        val summary = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("АКТИВНАЯ ВЕРСИЯ", 11, C.green, true)); add(gap(10))
            add(statusWorld); add(gap(5)); add(statusDetail); add(gap(11)); add(playerHint)
            add(gap(17))
            add(horizontal(BlockButton("Обновить").apply { addActionListener { onRefresh() } },
                BlockButton("Открыть мир").apply { addActionListener { onOpenWorld() } }))
        }
        add(summary); add(gap(24))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ИНВЕНТАРЬ ВЛАДЕЛЬЦА СТАРОГО МИРА", 11, C.gold, true)); add(gap(9))
            add(label("Если старый одиночный мир хранит вещи в level.dat, укажите UUID Minecraft аккаунта.", 12, C.muted))
            add(gap(12)); add(horizontal(ownerField, BlockButton("Перенести вещи").also { actionButtons += it }.apply {
                addActionListener { onImportOwner() }
            }))
        }); add(gap(24))
        add(label("КАК ЭТО РАБОТАЕТ", 12, C.gold, true)); add(gap(12))
        add(horizontal(
            stepCard("01", "Выбери мир", "Закрой Minecraft и укажи папку сохранения."),
            stepCard("02", "Передай другу", "По ID друга или по адресу в локальной сети."),
            stepCard("03", "Продолжай играть", "Получатель запустит мир из своей копии."),
        ))
    }

    private fun stepCard(number: String, title: String, description: String): JPanel = Surface(C.panelLight).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(label(number, 13, C.gold, true)); add(gap(8)); add(label(title, 16, C.text, true)); add(gap(7))
        add(label("<html><body style='width:230px'>$description</body></html>", 12, C.muted))
    }
}
