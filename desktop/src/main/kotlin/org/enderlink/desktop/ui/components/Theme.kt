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
    val background = Color(17, 15, 25)
    val panel = Color(33, 28, 45)
    val panelLight = Color(48, 39, 62)
    val sidebar = Color(24, 20, 34)
    val border = Color(89, 73, 106)
    val text = Color(246, 242, 250)
    val muted = Color(195, 184, 207)
    val green = Color(153, 231, 184)
    val greenDark = Color(57, 99, 85)
    val gold = Color(240, 204, 126)
    val red = Color(255, 157, 157)
    val input = Color(22, 18, 31)
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
    addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) { border = BorderFactory.createLineBorder(C.gold, UiScale.px(2)) }
        override fun focusLost(e: FocusEvent) { border = BorderFactory.createLineBorder(C.border) }
    })
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
        foreground = when {
            !active -> C.muted
            primary && model.isPressed -> C.text
            primary -> C.background
            else -> C.text
        }
        val bg = when {
            !active -> C.panel
            model.isPressed -> if (primary) C.greenDark else C.input
            model.isRollover -> if (primary) Color(185, 244, 206) else Color(69, 56, 88)
            primary -> C.green
            else -> C.panelLight
        }
        g.color = Color(0, 0, 0, 90)
        g.fillRect(2, 4, width - 4, height - 4)
        g.color = bg
        g.fillRect(0, 0, width - 2, height - 4)
        g.color = if (primary) Color(208, 255, 222) else C.border
        g.fillRect(0, 0, width - 2, 2)
        g.color = if (primary) C.greenDark else C.input
        g.fillRect(0, height - 6, width - 2, 2)
        if (hasFocus()) {
            g.color = C.gold
            g.stroke = BasicStroke(UiScale.px(2).toFloat())
            g.drawRect(1, 1, width - 5, height - 7)
        }
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
        g.color = C.greenDark
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
        if (w <= 0 || h <= 0) { g.dispose(); return }
        g.color = Color(25, 18, 40); g.fillRect(0, 0, w, h)
        g.color = Color(35, 25, 55); g.fillRect(0, 72, w, h - 72)
        g.color = Color(47, 32, 68); g.fillRect(0, 121, w, h - 121)
        for (i in 0..34) {
            val x = ((i * 83L + 29) % w).toInt()
            val y = ((i * 41L + 11) % 132).toInt()
            g.color = if (i % 5 == 0) C.green else Color(140, 110, 176)
            g.fillRect(x, y, if (i % 4 == 0) 3 else 2, 2)
        }
        // Obsidian towers and floating End stone islands stay on the right of the heading.
        val px = (w * 0.77).toInt().coerceAtLeast(360)
        g.color = Color(20, 17, 31); g.fillRect(px + 85, 57, 31, 111)
        g.color = Color(69, 49, 86); g.fillRect(px + 89, 57, 4, 111)
        g.color = Color(20, 17, 31); g.fillRect(px - 98, 94, 19, 74)
        g.color = Color(69, 49, 86); g.fillRect(px - 95, 94, 3, 74)
        g.color = Color(34, 28, 43); g.fillRect(px - 116, 151, 251, 19)
        g.color = Color(135, 127, 119); g.fillRect(px - 125, 145, 269, 7)
        g.color = Color(170, 158, 138); g.fillRect(px - 105, 141, 225, 4)
        g.color = Color(21, 17, 33); g.fillRect(px - 33, 41, 79, 101)
        g.color = Color(105, 75, 132); g.fillRect(px - 29, 45, 71, 93)
        g.color = Color(36, 24, 54); g.fillRect(px - 22, 52, 57, 79)
        for (i in 0..12) {
            g.color = if (i % 3 == 0) C.green else Color(143, 94, 190)
            g.fillRect(px - 16 + (i * 31 % 47), 57 + (i * 37 % 68), 3, 3)
        }
        g.color = C.greenDark; g.fillRect(px - 40, 141, 94, 4)
        g.color = Color(17, 15, 25); g.fillRect(0, h - 12, w, 12)
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

    fun waiting() {
        value = 0
        isIndeterminate = true
        foreground = C.gold
        getAccessibleContext().accessibleDescription = messages.text("status.preparing")
    }

    fun transferring(value: Int) {
        isIndeterminate = false
        foreground = C.green
        this.value = value
        getAccessibleContext().accessibleDescription = "${value / 10}%"
    }

    fun succeeded() {
        isIndeterminate = false
        foreground = C.green
        value = maximum
        getAccessibleContext().accessibleDescription = "100%"
    }

    fun failed() {
        isIndeterminate = false
        foreground = C.red
        getAccessibleContext().accessibleDescription = messages.text("status.errorShort")
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
