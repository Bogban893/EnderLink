package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.TextGUIGraphics

/** Three-row buttons highlight focus and hover without displaying a text cursor. */
internal class TuiButton(label: String, action: () -> Unit, val danger: Boolean = false) : Button(label, Runnable(action)) {
    var hovered = false
        set(value) { if (field != value) { field = value; invalidate() } }
    init { setRenderer(Renderer()) }
    override fun getCursorLocation(): TerminalPosition? = null

    private class Renderer : ButtonRenderer {
        override fun getCursorLocation(button: Button): TerminalPosition? = null
        override fun getPreferredSize(button: Button) = TerminalSize(maxOf(18, TerminalTextUtils.getColumnWidth(button.label) + 4), 3)

        override fun drawComponent(graphics: TextGUIGraphics, button: Button) {
            val width = graphics.size.columns
            val height = graphics.size.rows
            val style = button.themeDefinition
            val active = button.isFocused || (button as? TuiButton)?.hovered == true
            graphics.applyThemeStyle(style.normal)
            graphics.fill(' ')
            graphics.applyThemeStyle(style.getCustom(if (active) "focusBorder" else "border", style.normal))
            if (width < 2 || height < 3) return
            graphics.drawLine(1, 0, width - 2, 0, '─')
            graphics.drawLine(1, height - 1, width - 2, height - 1, '─')
            graphics.drawLine(0, 1, 0, height - 2, '│')
            graphics.drawLine(width - 1, 1, width - 1, height - 2, '│')
            graphics.setCharacter(0, 0, '┌')
            graphics.setCharacter(width - 1, 0, '┐')
            graphics.setCharacter(0, height - 1, '└')
            graphics.setCharacter(width - 1, height - 1, '┘')
            val selected = if ((button as? TuiButton)?.danger == true) style.getCustom("danger", style.active) else style.active
            val fillFocused = style.getBooleanProperty("fillFocused", false)
            if (active && fillFocused) {
                graphics.applyThemeStyle(selected)
                graphics.fillRectangle(TerminalPosition(1, 1), TerminalSize(width - 2, height - 2), ' ')
            }
            graphics.applyThemeStyle(if (active) selected else style.normal)
            val caption = TerminalTextUtils.fitString(button.label, maxOf(0, width - 4))
            graphics.putString(2, height / 2, caption)
        }
    }
}
