package org.enderlink.cli.tui

import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.Terminal
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal
import java.io.IOException

internal object TuiApplication {
    fun run() {
        if (System.console() == null || System.getenv("TERM") == "dumb") {
            throw IOException("Для cli tui нужен интерактивный терминал. Используйте cli --help для обычных команд.")
        }
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            throw IOException("Первый прототип cli tui работает в Linux/macOS и WSL. Нативная консоль Windows пока не подключена.")
        }
        val terminal = DefaultTerminalFactory().setForceTextTerminal(true)
            .setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP).createTerminal()
        run(terminal)
    }

    /** Owns and closes the terminal, restoring the previous screen even when the GUI fails. */
    internal fun run(terminal: Terminal) {
        try {
            terminal.use {
                TerminalScreen(it).use { screen ->
                    screen.startScreen()
                    TuiWindow().use { window -> MultiWindowTextGUI(screen).addWindowAndWait(window) }
                }
            }
        } catch (error: RuntimeException) {
            // Lanterna wraps drawing/input I/O failures while waiting for a window to close.
            val cause = error.cause
            if (cause is IOException) throw IOException("Ошибка терминала: ${cause.message}", error)
            throw error
        }
    }
}
