package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.gui2.AbstractBorder
import com.googlecode.lanterna.gui2.Border
import com.googlecode.lanterna.gui2.TextGUIGraphics

/** The frame reacts to focus inside it; its caption has a separate theme style. */
internal class TuiFrame(private val caption: String) : AbstractBorder() {
    override fun createDefaultRenderer(): Border.BorderRenderer = Renderer()

    private inner class Renderer : Border.BorderRenderer {
        override fun getWrappedComponentTopLeftOffset() = TerminalPosition.OFFSET_1x1
        override fun getWrappedComponentSize(size: TerminalSize) = TerminalSize(maxOf(0, size.columns - 2), maxOf(0, size.rows - 2))
        override fun getPreferredSize(border: Border): TerminalSize {
            val size = component?.preferredSize ?: TerminalSize.ZERO
            return TerminalSize(maxOf(size.columns + 2, TerminalTextUtils.getColumnWidth(caption) + 4), size.rows + 2)
        }

        override fun drawComponent(graphics: TextGUIGraphics, border: Border) {
            val width = graphics.size.columns
            val height = graphics.size.rows
            if (width < 2 || height < 2) return
            val style = themeDefinition
            val focus = basePane?.focusedInteractable
            graphics.applyThemeStyle(if (focus?.hasParent(this@TuiFrame) == true) style.active else style.normal)
            graphics.drawLine(1, 0, width - 2, 0, '─')
            graphics.drawLine(1, height - 1, width - 2, height - 1, '─')
            graphics.drawLine(0, 1, 0, height - 2, '│')
            graphics.drawLine(width - 1, 1, width - 1, height - 2, '│')
            graphics.setCharacter(0, 0, '┌')
            graphics.setCharacter(width - 1, 0, '┐')
            graphics.setCharacter(0, height - 1, '└')
            graphics.setCharacter(width - 1, height - 1, '┘')
            if (width > 4) {
                graphics.applyThemeStyle(style.getCustom("title", style.normal))
                graphics.putString(2, 0, TerminalTextUtils.fitString(caption, width - 4))
            }
            if (width > 2 && height > 2) component?.draw(graphics.newTextGraphics(
                getWrappedComponentTopLeftOffset(), getWrappedComponentSize(graphics.size),
            ))
        }
    }
}
