package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.BorderLayout
import com.googlecode.lanterna.gui2.Borders
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All widgets are changed on the GUI thread; a single worker owns the session and disk reads. */
internal class TuiWindow(private val session: TuiSession = TuiSession()) : BasicWindow("EnderLink"), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "enderlink-tui-reader").apply { isDaemon = true } }
    private val alive = AtomicBoolean(true)
    private var busy = false
    private val world = TextBox(TerminalSize(38, 4), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val friends = TextBox(TerminalSize(30, 4), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val output = TextBox(TerminalSize(60, 5), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val hint = Label("")
    private val status = Label("Готово. Первая версия: просмотр хранилища и друзей.")
    internal val commandInput = CommandInput(::submit)

    init {
        setHints(listOf(Window.Hint.FULL_SCREEN))
        val root = Panel(BorderLayout())
        val top = Panel(LinearLayout())
        val panels = Panel(GridLayout(2))
        panels.addComponent(world.withBorder(Borders.singleLine("Мир")))
        panels.addComponent(friends.withBorder(Borders.singleLine("Друзья")))
        top.addComponent(panels)
        val actions = Panel(GridLayout(6))
        actions.addComponent(Button("Хранилище") { edit("repository ") })
        actions.addComponent(Button("Обновить мир") { submit("world-status") })
        actions.addComponent(Button("Друзья") { submit("friends") })
        actions.addComponent(Button("Профиль") { edit("profile ") })
        actions.addComponent(Button("Помощь") { submit("help") })
        actions.addComponent(Button("Выход") { submit("exit") })
        top.addComponent(actions)
        top.addComponent(status)
        root.addComponent(top, BorderLayout.Location.TOP)
        root.addComponent(output.withBorder(Borders.singleLine("Результат")), BorderLayout.Location.CENTER)
        val bottom = Panel(LinearLayout())
        bottom.addComponent(commandInput.withBorder(Borders.singleLine("Команда EnderLink")))
        bottom.addComponent(hint)
        bottom.addComponent(Label("F2: строка  Shift+Tab: кнопки  Tab: дополнить  F10: выход"))
        root.addComponent(bottom, BorderLayout.Location.BOTTOM)
        component = root
        commandInput.setTextChangeListener { text, _ -> hint.text = TuiCommands.hint(text) }
        hint.text = TuiCommands.hint("")
        render(session.state)
        focusedInteractable = commandInput
    }

    override fun handleInput(key: KeyStroke): Boolean {
        when (key.keyType) {
            KeyType.F2 -> { focusedInteractable = commandInput; return true }
            KeyType.F10, KeyType.EOF -> { submit("exit"); return true }
            else -> return super.handleInput(key)
        }
    }

    private fun edit(line: String) { commandInput.replaceLine(line); focusedInteractable = commandInput }

    internal fun submit(line: String) {
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
