package org.enderlink.cli.tui

import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.SimpleTheme

internal object TuiTheme {
    fun create() = SimpleTheme(TextColor.ANSI.WHITE, TextColor.ANSI.BLACK).apply {
        addOverride(TuiButton::class.java, TextColor.ANSI.WHITE, TextColor.ANSI.BLACK).apply {
            setSelected(TextColor.ANSI.BLACK, TextColor.ANSI.WHITE)
            setActive(TextColor.ANSI.BLACK, TextColor.ANSI.WHITE)
            setPreLight(TextColor.ANSI.BLACK, TextColor.ANSI.WHITE)
        }
    }
}
