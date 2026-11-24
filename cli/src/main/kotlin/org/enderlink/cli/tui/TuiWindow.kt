package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.BorderLayout
import com.googlecode.lanterna.gui2.Borders
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All widgets are changed on the GUI thread; a single worker owns the session and disk reads. */
internal class TuiWindow(private val session: TuiSession = TuiSession()) : BasicWindow("EnderLink"), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "enderlink-tui-reader").apply { isDaemon = true } }
    private val alive = AtomicBoolean(true)
    private var busy = false
    private var focusCommandAfterInput = false
    private val world = TextBox(TerminalSize(30, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val friends = TextBox(TerminalSize(30, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val output = TextBox(TerminalSize(40, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val hint = Label("")
    private val status = Label("Готово. Первая версия: просмотр хранилища и друзей.")
    internal val commandInput = CommandInput(::submit)

    init {
        setHints(listOf(Window.Hint.FULL_SCREEN))
        val root = Panel(BorderLayout())
        fun grid(columns: Int) = GridLayout(columns).setLeftMarginSize(0).setRightMarginSize(0)
            .setTopMarginSize(0).setBottomMarginSize(0).setVerticalSpacing(0).setHorizontalSpacing(1)
        val body = Panel(TuiSplitLayout(Direction.VERTICAL))
        val panels = Panel(TuiSplitLayout(Direction.HORIZONTAL))
        panels.addComponent(world.withBorder(Borders.singleLine("Мир")))
        panels.addComponent(friends.withBorder(Borders.singleLine("Друзья")))
        body.addComponent(panels)
        body.addComponent(output.withBorder(Borders.singleLine("Результат")))
        root.addComponent(body, BorderLayout.Location.CENTER)
        val actions = Panel(grid(3))
        fun action(label: String, run: () -> Unit) {
            actions.addComponent(TuiButton(label, run))
        }
        action("Хранилище") { edit("repository ") }
        action("Обновить мир") { submit("world-status") }
        action("Друзья") { submit("friends") }
        action("Профиль") { edit("profile ") }
        action("Помощь") { submit("help") }
        action("Выход") { submit("exit") }
        val bottom = Panel(LinearLayout())
        val fill = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
        bottom.addComponent(status, fill)
        bottom.addComponent(EmptySpace(TerminalSize(0, 1)))
        bottom.addComponent(actions, LinearLayout.createLayoutData(LinearLayout.Alignment.Beginning))
        bottom.addComponent(commandInput.withBorder(Borders.singleLine("Команда EnderLink")), fill)
        bottom.addComponent(hint, fill)
        bottom.addComponent(Label("F2: строка  Shift+Tab: кнопки  Tab: дополнить  F10/Ctrl+Q: выход"))
        root.addComponent(bottom, BorderLayout.Location.BOTTOM)
        component = root
        commandInput.setTextChangeListener { text, _ -> hint.text = TuiCommands.hint(text) }
        hint.text = TuiCommands.hint("")
        render(session.state)
        focusedInteractable = commandInput
    }

    override fun handleInput(key: KeyStroke): Boolean {
        if (key.isCtrlDown && key.character?.lowercaseChar() == 'q') {
            submit("exit")
            return true
        }
        when (key.keyType) {
            KeyType.F2 -> { focusedInteractable = commandInput; return true }
            KeyType.F10, KeyType.EOF -> { submit("exit"); return true }
            else -> {
                val handled = super.handleInput(key)
                // Container mouse dispatch restores focus to the clicked button after its action.
                if (focusCommandAfterInput && (key !is MouseAction || key.isMouseUp)) {
                    focusCommandAfterInput = false
                    focusedInteractable = commandInput
                }
                return handled
            }
        }
    }

    private fun edit(line: String) {
        commandInput.replaceLine(line)
        focusCommandAfterInput = true
    }

    internal fun submit(line: String) {
        if (runCatching { TuiCommands.parse(line).first.name == "exit" }.getOrDefault(false)) {
            close()
            return
        }
        if (busy) { status.text = "Идёт чтение. Дождитесь завершения."; return }
        busy = true
        status.text = "Читаем данные…"
        val guiThread = textGUI.guiThread
        worker.submit {
            val result = runCatching { session.execute(line) }
            if (alive.get()) guiThread.invokeLater {
                if (alive.get()) {
                    busy = false
                    result.onSuccess { state ->
                        render(state)
                        status.text = if (state.error) "Ошибка" else "Готово"
                        if (state.close) close()
                    }.onFailure { error ->
                        output.text = "Не удалось выполнить действие: ${error.message}"
                        status.text = "Ошибка"
                    }
                }
            }
        }
    }

    private fun render(state: TuiState) {
        world.text = state.world
        friends.text = state.friends
        output.text = state.output
    }

    override fun close() {
        alive.set(false)
        worker.shutdownNow()
        super.close()
    }
}
