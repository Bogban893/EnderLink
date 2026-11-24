package org.enderlink.desktop

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.IdentityHashMap
import javax.swing.BorderFactory
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import kotlin.math.roundToInt

/** Sizes are recorded once, so repeated resizing never compounds rounding errors. */
internal object UiScale {
    var factor = 1f
        private set

    fun px(value: Int): Int = (value * factor).roundToInt()

    fun font(base: Font): Font = base.deriveFont(base.size2D * factor)

    fun factorFor(size: Dimension): Float = minOf(
        size.width / 1190f,
        size.height / 790f,
    ).coerceIn(1f, 1.6f)

    fun attach(window: Window) {
        val base = IdentityHashMap<JComponent, Style>()
        collect(window, base)
        var applying = false
        fun update() {
            val next = factorFor(window.size)
            if (applying || kotlin.math.abs(next - factor) < 0.015f) return
            applying = true
            try {
                factor = next
                base.forEach { (component, style) -> style.apply(component, next) }
                window.validate()
                window.repaint()
            } finally {
                applying = false
            }
        }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = update()
        })
        update()
    }

    private fun collect(component: Component, styles: IdentityHashMap<JComponent, Style>) {
        if (component is JComponent) styles[component] = Style(component)
        if (component is Container) component.components.forEach { collect(it, styles) }
    }

    private class Style(component: JComponent) {
        private val font = component.font?.takeIf { component.isFontSet }
        private val preferred = component.preferredSize.takeIf { component.isPreferredSizeSet }
        private val minimum = component.minimumSize.takeIf { component.isMinimumSizeSet }
        private val maximum = component.maximumSize.takeIf { component.isMaximumSizeSet }
        private val border = component.border
        private val iconTextGap = (component as? AbstractButton)?.iconTextGap

        fun apply(component: JComponent, scale: Float) {
            font?.let { component.font = it.deriveFont(it.size2D * scale) }
            preferred?.let { component.preferredSize = it.scaled(scale) }
            minimum?.let { component.minimumSize = it.scaled(scale) }
            maximum?.let { component.maximumSize = it.scaled(scale) }
            if (border != null) component.border = border.scaled(scale)
            if (component is AbstractButton && iconTextGap != null) component.iconTextGap = iconTextGap.scaled(scale)
        }
    }

    private fun Dimension.scaled(scale: Float) = Dimension(width.scaled(scale), height.scaled(scale))

    private fun Int.scaled(scale: Float): Int =
        if (this == Int.MAX_VALUE) this else (this * scale).roundToInt()

    private fun Border.scaled(scale: Float): Border = when (this) {
        is EmptyBorder -> borderInsets.let {
            EmptyBorder(it.top.scaled(scale), it.left.scaled(scale), it.bottom.scaled(scale), it.right.scaled(scale))
        }
        is LineBorder -> BorderFactory.createLineBorder(lineColor, thickness.scaled(scale).coerceAtLeast(1), roundedCorners)
        is CompoundBorder -> BorderFactory.createCompoundBorder(outsideBorder?.scaled(scale), insideBorder?.scaled(scale))
        else -> this
    }
}
