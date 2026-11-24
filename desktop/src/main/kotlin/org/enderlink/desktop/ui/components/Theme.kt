package org.enderlink.desktop.ui.components

import org.enderlink.desktop.i18n.messages
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.Icon
import javax.swing.JTextField
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.plaf.basic.BasicComboBoxUI

internal object C {
    val background = Color(16, 24, 25)
    val panel = Color(29, 39, 39)
    val panelLight = Color(38, 50, 47)
    val sidebar = Color(20, 31, 31)
    val border = Color(65, 83, 73)
    val text = Color(243, 241, 220)
    val muted = Color(161, 178, 164)
    val green = Color(132, 190, 96)
    val greenDark = Color(63, 113, 65)
    val gold = Color(239, 191, 94)
    val red = Color(226, 121, 108)
    val input = Color(18, 28, 29)
}

internal fun font(size: Int, bold: Boolean = false): Font = Font(
    "Dialog", if (bold) Font.BOLD else Font.PLAIN, UiScale.px(size),
)

private fun inputBorder(focused: Boolean) = BorderFactory.createCompoundBorder(
    BorderFactory.createLineBorder(if (focused) C.green else C.border, UiScale.px(if (focused) 2 else 1)),
    EmptyBorder(UiScale.px(if (focused) 8 else 9), UiScale.px(if (focused) 11 else 12),
        UiScale.px(if (focused) 8 else 9), UiScale.px(if (focused) 11 else 12)),
)

internal fun label(text: String, size: Int = 14, color: Color = C.text, bold: Boolean = false): JLabel =
    JLabel(text).apply { font = font(size, bold); foreground = color; alignmentX = 0f }

internal fun field(value: String = ""): JTextField = JTextField(value).apply {
    font = font(14)
    foreground = C.text
    background = C.input
    caretColor = C.green
    selectionColor = C.greenDark
    selectedTextColor = C.text
    border = inputBorder(false)
    preferredSize = Dimension(200, 42)
    maximumSize = Dimension(Int.MAX_VALUE, 42)
    addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) { border = inputBorder(true) }
        override fun focusLost(e: FocusEvent) { border = inputBorder(false) }
    })
}

internal fun <T> combo(values: Array<T>): JComboBox<T> = JComboBox(values).apply {
    font = font(14)
    foreground = C.text
    background = C.input
    preferredSize = Dimension(200, 42)
    maximumSize = Dimension(Int.MAX_VALUE, 42)
    border = BorderFactory.createLineBorder(C.border)
    renderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component = (super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel).apply {
            background = if (isSelected) C.greenDark else C.input
            foreground = C.text
            border = EmptyBorder(UiScale.px(8), UiScale.px(12), UiScale.px(8), UiScale.px(12))
            font = font(14)
        }
    }
    setUI(object : BasicComboBoxUI() {
        override fun paintCurrentValueBackground(graphics: Graphics, bounds: java.awt.Rectangle, hasFocus: Boolean) {
            graphics.color = C.input
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
        }
        override fun createArrowButton(): JButton = JButton("▾").apply {
            foreground = C.text
            background = C.panelLight
            border = EmptyBorder(0, 9, 0, 9)
            isFocusPainted = false
        }
    })
    background = C.input
    foreground = C.text
}

internal class BlockButton(text: String, private val primary: Boolean = false) : JButton(text) {
    init {
        font = font(14, true)
        foreground = if (primary) C.background else C.text
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = EmptyBorder(10, 18, 12, 18)
        preferredSize = Dimension(maxOf(152, getFontMetrics(font).stringWidth(text) + 56), 43)
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        val active = isEnabled
        val bg = when {
            !active -> C.border
            model.isPressed -> if (primary) C.greenDark else C.input
            model.isRollover -> if (primary) Color(156, 210, 116) else C.panelLight
            primary -> C.green
            else -> C.panelLight
        }
        g.color = Color(0, 0, 0, 90)
        g.fillRect(2, 4, width - 4, height - 4)
        g.color = bg
        g.fillRect(0, 0, width - 2, height - 4)
        g.color = if (primary) Color(193, 232, 148) else C.border
        g.fillRect(0, 0, width - 2, 2)
        g.color = if (primary) C.greenDark else C.input
        g.fillRect(0, height - 6, width - 2, 2)
        g.dispose()
        super.paintComponent(graphics)
    }
}

internal class Surface(private val fill: Color = C.panel) : JPanel() {
    init {
        isOpaque = false
        alignmentX = 0f
        border = EmptyBorder(22, 24, 22, 24)
    }
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.color = Color(0, 0, 0, 65)
        g.fillRect(5, 5, width - 5, height - 5)
        g.color = fill
        g.fillRect(0, 0, width - 5, height - 5)
        g.color = C.border
        g.drawRect(0, 0, width - 6, height - 6)
        g.color = Color(92, 117, 92)
        g.fillRect(1, 1, width - 7, 2)
        g.dispose()
        super.paintComponent(graphics)
    }
}

internal class PixelScene : JComponent() {
    init {
        preferredSize = Dimension(650, 182)
        minimumSize = Dimension(300, 182)
        maximumSize = Dimension(Int.MAX_VALUE, 182)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.scale(UiScale.factor.toDouble(), UiScale.factor.toDouble())
        val w = (width / UiScale.factor).toInt(); val h = (height / UiScale.factor).toInt()
        g.color = Color(48, 91, 97); g.fillRect(0, 0, w, h)
        g.color = Color(76, 129, 135); g.fillRect(0, 20, w, h - 20)
        g.color = Color(102, 153, 151); g.fillRect(0, 62, w, h - 62)
        // Distant square clouds and blocky mountain silhouettes.
        g.color = Color(175, 199, 177)
        for ((x, y) in listOf(32 to 30, 250 to 23, 500 to 50, 740 to 24)) {
            g.fillRect(x, y + 8, 88, 12); g.fillRect(x + 14, y, 53, 12)
        }
        g.color = Color(76, 116, 109)
        for (x in 0..w step 22) {
            val rise = ((x * 17L + 43) % 53).toInt()
            g.fillRect(x, 112 - rise / 2, 24, h)
        }
        g.color = Color(45, 88, 74)
        for (x in 0..w step 32) {
            val rise = ((x * 11L + 23) % 50).toInt()
            g.fillRect(x, 134 - rise / 3, 34, h)
        }
        // A small End portal. It is geometric so it remains crisp at any scale.
        val px = (w * 0.76).toInt().coerceAtLeast(300)
        g.color = Color(21, 27, 33); g.fillRect(px - 32, 44, 116, 109)
        g.color = Color(101, 80, 116); g.fillRect(px - 26, 50, 104, 97)
        g.color = Color(45, 28, 59); g.fillRect(px - 18, 57, 88, 85)
        g.color = Color(118, 77, 151)
        for (i in 0..11) {
            val x = px - 14 + ((i * 37) % 81)
            val y = 62 + ((i * 43) % 74)
            g.fillRect(x, y, if (i % 3 == 0) 5 else 3, 3)
        }
        g.color = Color(37, 55, 49); g.fillRect(px - 42, 148, 136, 10)
        g.color = Color(60, 125, 66); g.fillRect(0, h - 34, w, 12)
        g.color = Color(96, 159, 70); g.fillRect(0, h - 36, w, 5)
        g.color = Color(95, 71, 51); g.fillRect(0, h - 22, w, 22)
        for (x in 0..w step 36) {
            g.color = if ((x / 36) % 2 == 0) Color(113, 84, 55) else Color(70, 58, 47)
            g.fillRect(x + 8, h - 15, 9, 5)
        }
        g.color = C.text
        g.font = Font("Dialog", Font.BOLD, 26)
        g.drawString(messages.text("brand.sceneTitle"), 28, 86)
        g.font = Font("Dialog", Font.PLAIN, 13)
        g.drawString(messages.text("brand.sceneSubtitle"), 30, 112)
        g.color = C.border; g.stroke = BasicStroke(2f); g.drawRect(1, 1, w - 3, h - 3)
        g.dispose()
    }
}

internal class PixelRadioIcon(private val selected: Boolean) : Icon {
    override fun getIconWidth() = UiScale.px(20)
    override fun getIconHeight() = UiScale.px(20)

    override fun paintIcon(component: java.awt.Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        val size = iconWidth
        val edge = UiScale.px(2).coerceAtLeast(1)
        g.color = if (component.isEnabled) C.border else C.panelLight
        g.fillRect(x, y, size, size)
        g.color = C.input
        g.fillRect(x + edge, y + edge, size - edge * 2, size - edge * 2)
        if (selected) {
            g.color = if (component.isEnabled) C.green else C.muted
            val inset = UiScale.px(6)
            g.fillRect(x + inset, y + inset, size - inset * 2, size - inset * 2)
        }
        g.dispose()
    }
}

internal class TransferBar : JProgressBar(0, 1000) {
    init {
        isStringPainted = false
        foreground = C.green
        background = C.input
        border = BorderFactory.createLineBorder(C.border)
        preferredSize = Dimension(300, 12)
        maximumSize = Dimension(Int.MAX_VALUE, 12)
    }
}

internal class DarkScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        thumbColor = C.border
        trackColor = C.background
    }

    override fun createDecreaseButton(orientation: Int): JButton = zeroButton()
    override fun createIncreaseButton(orientation: Int): JButton = zeroButton()

    private fun zeroButton() = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }
}
