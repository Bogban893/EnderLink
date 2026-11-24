package org.enderlink.desktop.ui.components

import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

internal fun page(title: String, description: String): JPanel = stack().apply {
    border = EmptyBorder(0, 0, 25, 4)
    add(label(title, 27, C.text, true)); add(gap(6)); add(label(description, 14, C.muted)); add(gap(24))
}

internal fun stack(): JPanel = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    alignmentX = 0f
}

internal fun gap(height: Int) = Box.createRigidArea(Dimension(1, height))

internal fun horizontal(vararg components: java.awt.Component): JPanel = JPanel().apply {
    isOpaque = false
    alignmentX = 0f
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    components.forEachIndexed { index, component ->
        if (index > 0) add(Box.createRigidArea(Dimension(11, 1)))
        add(component)
    }
    maximumSize = Dimension(Int.MAX_VALUE, components.maxOfOrNull { it.preferredSize.height } ?: 45)
}

internal fun scroll(panel: JPanel): JScrollPane = JScrollPane(panel).apply {
    isOpaque = false
    viewport.isOpaque = false
    border = null
    verticalScrollBar.unitIncrement = 18
    verticalScrollBar.setUI(DarkScrollBarUI())
    verticalScrollBar.preferredSize = Dimension(9, 0)
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
}
