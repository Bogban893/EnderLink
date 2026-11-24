package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.BorderLayout
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.TextGUIGraphics
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder
import com.googlecode.lanterna.graphics.SimpleTheme
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All widgets are changed on the GUI thread; a single worker owns the session and disk reads. */
internal class TuiWindow(private val session: TuiSession = TuiSession()) : BasicWindow("EnderLink"), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "enderlink-tui-reader").apply { isDaemon = true } }
    private val alive = AtomicBoolean(true)
    private var busy = false
    private var renderedTheme: TuiThemeId? = null
    private val buttons = mutableListOf<TuiButton>()
    private val mainContent = Panel(BorderLayout())
    private val sizeWarning = Label("")
    private var savedFocus: Interactable? = null
    private var focusCommandAfterInput = false
    private val world = TextBox(TerminalSize(30, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val friends = TextBox(TerminalSize(30, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val output = TextBox(TerminalSize(40, 2), TextBox.Style.MULTI_LINE).setReadOnly(true)
    private val hint = Label("").setPreferredSize(TerminalSize(1, 1))
    private val status = Label("Готово. Первая версия: просмотр хранилища и друзей.").setPreferredSize(TerminalSize(1, 1))
    private val footer = Label("F2: строка  Shift+Tab: кнопки  Tab: дополнить  F10/Ctrl+Q: выход")
        .setPreferredSize(TerminalSize(1, 1))
    internal val commandInput = CommandInput(::submit)

    init {
        setHints(listOf(Window.Hint.FULL_SCREEN))
        val root = mainContent
        fun grid(columns: Int) = GridLayout(columns).setLeftMarginSize(0).setRightMarginSize(0)
            .setTopMarginSize(0).setBottomMarginSize(0).setVerticalSpacing(0).setHorizontalSpacing(1)
        val body = Panel(TuiSplitLayout(Direction.VERTICAL))
        val panels = Panel(TuiSplitLayout(Direction.HORIZONTAL))
        panels.addComponent(world.withBorder(TuiFrame("Мир")))
        panels.addComponent(friends.withBorder(TuiFrame("Друзья")))
        body.addComponent(panels)
        body.addComponent(output.withBorder(TuiFrame("Результат")))
        root.addComponent(body, BorderLayout.Location.CENTER)
        val actions = Panel(grid(4))
        fun action(label: String, danger: Boolean = false, run: () -> Unit) {
            val button = TuiButton(label, run, danger)
            buttons += button
            actions.addComponent(button)
        }
        action("Хранилище") { edit("repository ") }
        action("Обновить мир") { submit("world-status") }
        action("Друзья") { submit("friends") }
        action("Профиль") { edit("profile ") }
        action("Помощь") { submit("help") }
        action("Тема") { chooseTheme() }
        action("Выход", danger = true) { submit("exit") }
        val bottom = Panel(LinearLayout())
        val fill = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
        bottom.addComponent(EmptySpace(TerminalSize(0, 1)))
        bottom.addComponent(actions, LinearLayout.createLayoutData(LinearLayout.Alignment.Beginning))
        bottom.addComponent(commandInput.withBorder(TuiFrame("Команда EnderLink")), fill)
        bottom.addComponent(status, fill)
        bottom.addComponent(hint, fill)
        bottom.addComponent(footer, fill)
        root.addComponent(bottom, BorderLayout.Location.BOTTOM)
        component = root
        commandInput.setTextChangeListener { text, _ -> hint.text = TuiCommands.hint(text) }
        hint.text = TuiCommands.hint("")
        render(session.state)
        focusedInteractable = commandInput
    }

    override fun draw(graphics: TextGUIGraphics) {
        val area = textGUI?.screen?.terminalSize ?: graphics.size
        val tooSmall = area.columns < MINIMUM_SIZE.columns || area.rows < MINIMUM_SIZE.rows
        if (tooSmall) {
            if (component !== sizeWarning) {
                savedFocus = focusedInteractable
                buttons.forEach { it.hovered = false }
                component = sizeWarning
            }
            sizeWarning.text = "Минимум: ${MINIMUM_SIZE.columns}×${MINIMUM_SIZE.rows}\n" +
                "Сейчас: ${area.columns}×${area.rows}\nУвеличьте окно терминала.\nF10 / Ctrl+Q — выход"
        } else if (component !== mainContent) {
            component = mainContent
            focusedInteractable = savedFocus ?: commandInput
            savedFocus = null
        }
        super.draw(graphics)
    }

    override fun handleInput(key: KeyStroke): Boolean {
        if (key.keyType == KeyType.F10 || key.keyType == KeyType.EOF ||
            key.isCtrlDown && key.character?.lowercaseChar() == 'q') {
            submit("exit")
            return true
        }
        // Hidden controls must not receive keystrokes or clicks while the terminal is too small.
        if (component === sizeWarning) return true
        if (key is MouseAction && key.actionType == MouseActionType.MOVE) {
            for (button in buttons) {
                val origin = button.globalPosition
                button.hovered = key.position.column in origin.column until origin.column + button.size.columns &&
                    key.position.row in origin.row until origin.row + button.size.rows
            }
        }
        when (key.keyType) {
            KeyType.F2 -> { focusedInteractable = commandInput; return true }
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

    private fun chooseTheme() {
        val chooser = ActionListDialogBuilder().setTitle("Тема оформления").setCanCancel(true)
        for (id in TuiThemeId.entries) {
            chooser.addAction(id.displayName + if (id == renderedTheme) " (выбрана)" else "") { submit("theme ${id.id}") }
        }
        chooser.build().showDialog(textGUI)
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
        val palette = TuiTheme.palette(state.theme)
        if (renderedTheme != state.theme) {
            val updated = TuiTheme.create(state.theme)
            setTheme(updated)
            textGUI?.theme = updated
            renderedTheme = state.theme
        }
        world.setTheme(SimpleTheme(if (state.repository == null) palette.muted else palette.text, palette.background))
        friends.setTheme(SimpleTheme(if (state.friendsLoaded) palette.text else palette.muted, palette.background))
        output.setTheme(SimpleTheme(if (state.error) palette.danger else palette.text, palette.background))
        hint.foregroundColor = palette.muted
        hint.backgroundColor = palette.background
        footer.foregroundColor = palette.muted
        footer.backgroundColor = palette.background
        status.foregroundColor = if (state.error) palette.danger else palette.muted
        status.backgroundColor = palette.background
        world.text = state.world
        friends.text = state.friends
        output.text = state.output
    }

    override fun close() {
        alive.set(false)
        worker.shutdownNow()
        super.close()
    }

    private companion object {
        val MINIMUM_SIZE = TerminalSize(80, 24)
    }
}
