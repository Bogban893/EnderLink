package org.enderlink.cli.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.SimpleTheme
import com.googlecode.lanterna.gui2.AbstractBorder

internal enum class TuiThemeId(val id: String, val displayName: String) {
    MONO("mono", "Монохромная"),
    ENDER("ender", "Ender / Void"),
    BTOP("btop", "btop inspired"),
    ;

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

internal data class TuiPalette(
    val background: TextColor,
    val text: TextColor,
    val border: TextColor,
    val focusBorder: TextColor,
    val title: TextColor,
    val muted: TextColor,
    val buttonBorder: TextColor,
    val buttonText: TextColor,
    val focusFill: TextColor,
    val focusText: TextColor,
    val danger: TextColor,
)

internal object TuiTheme {
    fun palette(id: TuiThemeId): TuiPalette {
        fun rgb(hex: String) = TextColor.Factory.fromString(hex)
        return when (id) {
            TuiThemeId.MONO -> TuiPalette(
                TextColor.ANSI.BLACK, TextColor.ANSI.WHITE, TextColor.ANSI.WHITE, TextColor.ANSI.WHITE,
                TextColor.ANSI.WHITE, TextColor.ANSI.WHITE, TextColor.ANSI.WHITE, TextColor.ANSI.WHITE,
                TextColor.ANSI.WHITE, TextColor.ANSI.BLACK, TextColor.ANSI.WHITE,
            )
            TuiThemeId.ENDER -> TuiPalette(
                background = rgb("#14111b"), text = rgb("#e2e8f0"), border = rgb("#3b3354"),
                focusBorder = rgb("#a855f7"), title = rgb("#2dd4bf"), muted = rgb("#7c7494"),
                buttonBorder = rgb("#4c436b"), buttonText = rgb("#cbd5e1"), focusFill = rgb("#6b21a8"),
                focusText = rgb("#ffffff"), danger = rgb("#ef4444"),
            )
            TuiThemeId.BTOP -> TuiPalette(
                background = rgb("#10131b"), text = rgb("#cdd6f4"), border = rgb("#303844"),
                focusBorder = rgb("#5ccfe6"), title = rgb("#8bd5ca"), muted = rgb("#6c7086"),
                buttonBorder = rgb("#455166"), buttonText = rgb("#cdd6f4"), focusFill = rgb("#245565"),
                focusText = rgb("#a6e3a1"), danger = rgb("#ef4444"),
            )
        }
    }

    fun create(id: TuiThemeId = TuiThemeId.MONO) = palette(id).let { p ->
        val bold = if (id == TuiThemeId.MONO) emptyArray() else arrayOf(SGR.BOLD)
        SimpleTheme(p.text, p.background).apply {
            addOverride(AbstractBorder::class.java, p.border, p.background).setActive(p.title, p.background, *bold)
            addOverride(TuiFrame::class.java, p.border, p.background).apply {
                setActive(p.focusBorder, p.background)
                setCustom("title", p.title, p.background, *bold)
            }
            addOverride(TuiButton::class.java, p.buttonText, p.background).apply {
                setSelected(p.focusText, p.focusFill, *bold)
                setActive(p.focusText, p.focusFill, *bold)
                setPreLight(p.focusText, p.focusFill, *bold)
                setCustom("border", p.buttonBorder, p.background)
                setCustom("focusBorder", p.focusBorder, p.background)
                setCustom("danger", p.focusText, p.danger, *bold)
                setBooleanProperty("fillFocused", id != TuiThemeId.MONO)
            }
        }
    }
}
