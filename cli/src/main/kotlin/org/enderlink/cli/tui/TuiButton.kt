package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.TextGUIGraphics

/** Three-row buttons have a neutral frame and highlight only their focused caption. */
internal class TuiButton(label: String, action: () -> Unit) : Button(label, Runnable(action)) {
    init { setRenderer(Renderer()) }
    override fun getCursorLocation(): TerminalPosition? = null

    private class Renderer : ButtonRenderer {
        override fun getCursorLocation(button: Button): TerminalPosition? = null
        override fun getPreferredSize(button: Button) = TerminalSize(maxOf(18, TerminalTextUtils.getColumnWidth(button.label) + 4), 3)

        override fun drawComponent(graphics: TextGUIGraphics, button: Button) {
            val width = graphics.size.columns
            val height = graphics.size.rows
            graphics.applyThemeStyle(button.themeDefinition.normal)
            graphics.fill(' ')
            if (width < 2 || height < 3) return
            graphics.drawLine(1, 0, width - 2, 0, '─')
            graphics.drawLine(1, height - 1, width - 2, height - 1, '─')
            graphics.drawLine(0, 1, 0, height - 2, '│')
            graphics.drawLine(width - 1, 1, width - 1, height - 2, '│')
            graphics.setCharacter(0, 0, '┌')
            graphics.setCharacter(width - 1, 0, '┐')
            graphics.setCharacter(0, height - 1, '└')
            graphics.setCharacter(width - 1, height - 1, '┘')
            if (button.isFocused) graphics.applyThemeStyle(button.themeDefinition.active)
            val caption = TerminalTextUtils.fitString(button.label, maxOf(0, width - 4))
            graphics.putString(2, height / 2, caption)
        }
    }
}
