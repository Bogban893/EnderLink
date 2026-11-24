package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.LayoutManager

/** Two growing panes share the exact available cells, including odd terminal sizes. */
internal class TuiSplitLayout(private val direction: Direction) : LayoutManager {
    override fun hasChanged() = false

    override fun getPreferredSize(components: List<Component>): TerminalSize {
        require(components.size == 2)
        val first = components[0].preferredSize
        val second = components[1].preferredSize
        return if (direction == Direction.HORIZONTAL) {
            TerminalSize(first.columns + second.columns + 1, maxOf(first.rows, second.rows))
        } else TerminalSize(maxOf(first.columns, second.columns), first.rows + second.rows + 1)
    }

    override fun doLayout(area: TerminalSize, components: List<Component>) {
        require(components.size == 2)
        val horizontal = direction == Direction.HORIZONTAL
        val total = if (horizontal) area.columns else area.rows
        val gap = minOf(1, total)
        val first = (total - gap) / 2
        val second = total - gap - first
        components[0].position = TerminalPosition.TOP_LEFT_CORNER
        components[0].size = if (horizontal) TerminalSize(first, area.rows) else TerminalSize(area.columns, first)
        components[1].position = if (horizontal) TerminalPosition(first + gap, 0) else TerminalPosition(0, first + gap)
        components[1].size = if (horizontal) TerminalSize(second, area.rows) else TerminalSize(area.columns, second)
    }
}
