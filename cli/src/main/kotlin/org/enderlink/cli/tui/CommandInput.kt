package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Interactable.Result
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType

internal class CommandInput(private val submit: (String) -> Unit) : TextBox(TerminalSize(40, 1)) {
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var draft = ""

    override fun handleKeyStroke(key: KeyStroke): Result {
        when {
            key.isCtrlDown && key.character?.lowercaseChar() == 'w' -> {
                val line = text
                val end = caretPosition.column
                var start = end
                while (start > 0 && line[start - 1].isWhitespace()) start--
                while (start > 0 && !line[start - 1].isWhitespace()) start--
                text = line.removeRange(start, end)
                setCaretPosition(start)
            }
            key.keyType == KeyType.Enter -> {
                val line = text
                if (line.isNotBlank()) {
                    if (runCatching { TuiCommands.parse(line) }.isFailure) {
                        submit(line)
                        return Result.HANDLED
                    }
                    if (history.lastOrNull() != line) history += line
                    if (history.size > 100) history.removeAt(0)
                    historyIndex = history.size
                    draft = ""
                    text = ""
                    submit(line)
                }
            }
            key.keyType == KeyType.Tab -> {
                if (caretPosition.column == text.length) {
                    val options = TuiCommands.completions(text)
                    if (options.size == 1) replaceLine(options.single() + " ")
                }
            }
            key.keyType == KeyType.ArrowUp -> {
                if (historyIndex == history.size) draft = text
                if (historyIndex > 0) replaceLine(history[--historyIndex])
            }
            key.keyType == KeyType.ArrowDown -> {
                if (historyIndex < history.size) historyIndex++
                replaceLine(if (historyIndex == history.size) draft else history[historyIndex])
            }
            key.keyType == KeyType.Escape || key.isCtrlDown && key.character?.lowercaseChar() == 'c' -> {
                historyIndex = history.size
                replaceLine("")
            }
            else -> return super.handleKeyStroke(key)
        }
        return Result.HANDLED
    }

    fun replaceLine(line: String) { text = line; setCaretPosition(line.length) }
}
