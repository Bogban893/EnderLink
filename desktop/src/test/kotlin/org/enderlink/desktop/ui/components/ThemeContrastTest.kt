package org.enderlink.desktop.ui.components

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    @Test
    fun `ordinary text and status colors meet readable contrast on their surfaces`() {
        val pairs = listOf(
            C.text to C.panel,
            C.text to C.input,
            C.muted to C.panel,
            C.muted to C.panelLight,
            C.muted to C.sidebar,
            C.green to C.panel,
            C.gold to C.panelLight,
            C.red to C.panelLight,
            C.background to C.green,
            C.text to C.greenDark,
        )
        pairs.forEach { (foreground, background) ->
            val ratio = contrast(foreground, background)
            assertTrue(ratio >= 4.5, "Contrast $ratio is too low for $foreground on $background")
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
