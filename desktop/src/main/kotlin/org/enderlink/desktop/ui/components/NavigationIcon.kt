package org.enderlink.desktop.ui.components

import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

/** Small pixel glyphs share the sidebar text color and remain legible at UI scale. */
internal class NavigationIcon(private val page: String) : Icon {
    private val pixels = when (page) {
        "home" -> listOf("  X  ", " XXX ", "XXXXX", " X X ", " X X ")
        "send" -> listOf("   X ", "  XX ", "XXXXX", "  XX ", "   X ")
        "receive" -> listOf(" X   ", " XX  ", "XXXXX", " XX  ", " X   ")
        "server" -> listOf("XXXXX", "X   X", "X X X", "X   X", "XXXXX")
        else -> listOf(" X X ", " X X ", "XXXXX", "X X X", "X X X")
    }

    override fun getIconWidth(): Int = UiScale.px(18)
    override fun getIconHeight(): Int = UiScale.px(18)

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create()
        g.color = component.foreground
        val cell = UiScale.px(3)
        pixels.forEachIndexed { row, line ->
            line.forEachIndexed { column, pixel ->
                if (pixel == 'X') g.fillRect(x + column * cell, y + row * cell, cell, cell)
            }
        }
        g.dispose()
    }
}
