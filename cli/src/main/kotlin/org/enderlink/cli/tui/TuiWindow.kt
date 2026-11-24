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
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder
import org.enderlink.cli.command.OperationControl
import com.googlecode.lanterna.graphics.SimpleTheme
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All widgets are changed on the GUI thread; a single worker owns the session and disk reads. */
internal class TuiWindow(private val session: TuiSession = TuiSession()) : BasicWindow("EnderLink"), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "enderlink-tui-worker") }
    private val canceller = Executors.newSingleThreadExecutor { job -> Thread(job, "enderlink-tui-cancel") }
    private var activeControl: OperationControl? = null
    private var exitAfterOperation = false
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
    private val status = Label("Готово").setPreferredSize(TerminalSize(1, 1))
    private val footer = Label("F2: строка  Tab: дополнить  Ctrl+X: отмена  F10/Ctrl+Q: выход")
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
        action("Мир") { worldActions() }
        action("Передача") { transferActions() }
        action("Друзья") { friendActions() }
        action("Профиль") { profileActions() }
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
        commandInput.requestCompletion = ::complete
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
        if (key.isCtrlDown && key.character?.lowercaseChar() == 'x') { cancelOperation(); return true }
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

    private fun menu(title: String, actions: List<Pair<String, () -> Unit>>) {
        val chooser = ActionListDialogBuilder().setTitle(title).setCanCancel(true)
        actions.forEach { (label, action) -> chooser.addAction(label, Runnable(action)) }
        chooser.build().showDialog(textGUI)
    }

    private fun ask(title: String, description: String): String? = TextInputDialogBuilder()
        .setTitle(title).setDescription(description).build().showDialog(textGUI)?.takeIf { it.isNotBlank() }

    private fun worldActions() = menu("Мир", listOf(
        "Обновить состояние" to { submit("world-status") },
        "Версии" to { submit("world-versions") },
        "Опубликовать закрытый мир" to {
            ask("Публикация", "Закройте мир Minecraft / остановите сервер.\nПуть к закрытому исходному миру:")?.let {
                edit("world-publish --source ${TuiWords.quote(it)} --closed")
            }
        },
        "Выбрать историческую версию" to { edit("world-checkout --version ") },
        "Импортировать владельца старого мира" to { edit("world-import-owner --owner-uuid ") },
    ))

    private fun transferActions() = menu("Передача мира", listOf(
        "Отправить другу (iroh)" to { edit("world-send --peer ") },
        "Отправить по TCP" to { ask("TCP", "Адрес получателя в доверенной сети:")?.let { edit("world-send --host ${TuiWords.quote(it)}") } },
        "Принимать через iroh" to { edit("world-receive --iroh") },
        "Принимать по TCP (доверенная сеть)" to { edit("world-receive") },
        "Отменить операцию" to { cancelOperation() },
    ))

    private fun friendActions() = menu("Друзья", listOf(
        "Обновить список" to { submit("friends") },
        "Добавить друга" to { edit("add ") },
        "Удалить друга" to { edit("remove ") },
    ))

    private fun profileActions() = menu("Профиль", listOf(
        "Ваш ID" to { submit("id") },
        "Выбрать каталог профиля" to { edit("profile ") },
        "Создать ID в выбранном каталоге" to { edit("profile-init") },
    ))

    private fun complete(line: String) {
        if (busy) { hint.text = "Дополнение станет доступно после завершения операции."; return }
        val guiThread = textGUI.guiThread
        worker.submit {
            val options = runCatching { TuiCompletions.complete(line, session.state) }
            if (alive.get()) guiThread.invokeLater {
                if (alive.get() && commandInput.text == line) options.onSuccess {
                    commandInput.applyCompletion(it)
                    if (it.size != 1) hint.text = it.take(5).joinToString(" | ").ifEmpty { "Нет вариантов дополнения." }
                }.onFailure { hint.text = "Не удалось дополнить: ${it.message}" }
            }
        }
    }

    private fun cancelOperation() {
        val control = activeControl
        if (control == null) { status.text = "Нет выполняющейся операции."; return }
        if (!control.requestCancel()) { status.text = "Завершается запись мира. Дождитесь завершения."; return }
        status.text = "Отмена операции…"
        canceller.submit { control.closeCancelledResources() }
    }

    private fun edit(line: String) {
        commandInput.replaceLine(line)
        focusCommandAfterInput = true
    }

    internal fun submit(line: String) {
        val command = runCatching { TuiCommands.parse(line).first.name }.getOrNull()
        if (command == "exit") {
            if (activeControl?.requestCancel() == false) {
                exitAfterOperation = true
                status.text = "Завершается запись мира. После этого окно закроется."
                return
            }
            close()
            return
        }
        if (command == "cancel") { cancelOperation(); return }
        if (busy) { commandInput.replaceLine(line); status.text = "Идёт операция. Дождитесь завершения или введите cancel."; return }
        busy = true
        render(session.state.copy(error = false, output = "Выполняем команду: ${command ?: line}"))
        val control = OperationControl()
        activeControl = control
        status.text = "Выполняем…"
        val guiThread = textGUI.guiThread
        worker.submit {
            val result = runCatching { session.execute(line, onOutput = { snapshot ->
                if (alive.get()) guiThread.invokeLater {
                    if (alive.get() && activeControl === control) {
                        output.text = snapshot
                        output.setCaretPosition(output.lineCount - 1, 0)
                        status.text = snapshot.lineSequence().lastOrNull().orEmpty()
                    }
                }
            }, control = control) }
            if (alive.get()) guiThread.invokeLater {
                if (alive.get()) {
                    busy = false
                    activeControl = null
                    result.onSuccess { state ->
                        render(state)
                        status.text = if (state.cancelled) "Отменено" else if (state.error) "Ошибка" else "Готово"
                        if (state.close || exitAfterOperation) close()
                    }.onFailure { error ->
                        render(session.state.copy(error = true, output = "Не удалось выполнить действие: ${error.message}"))
                        status.text = "Ошибка"
                        if (exitAfterOperation) close()
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
        if (!alive.getAndSet(false)) return
        activeControl?.let { if (it.requestCancel()) canceller.submit { it.closeCancelledResources() } }
        worker.shutdown()
        canceller.shutdown()
        super.close()
    }

    private companion object {
        val MINIMUM_SIZE = TerminalSize(80, 24)
    }
}
