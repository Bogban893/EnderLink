package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.Container
import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TuiWindowTest {
    @Test
    fun `theme button opens chooser and applies selected theme`() = withGui { terminal, _, gui, window ->
        val button = window.component.descendants().filterIsInstance<TuiButton>().first { it.label == "Тема" }
        window.focusedInteractable = button
        terminal.addInput(KeyStroke(KeyType.ArrowDown))
        terminal.addInput(KeyStroke(KeyType.Enter))
        gui.handleInput(KeyStroke(KeyType.Enter))
        val result = window.component.descendants().filterIsInstance<TextBox>().filter { it.isReadOnly }.last()
        awaitUi(gui) { result.text == "Тема: Ender / Void" }
        assertEquals(1, gui.windows.size)
        assertTrue(gui.windows.contains(window))
    }

    @Test
    fun `live theme switch recolors frames headings placeholders and exit hover`() = withGui { terminal, _, gui, window ->
        val fields = window.component.descendants().filterIsInstance<TextBox>().filter { it.isReadOnly }.toList()
        val frame = fields[0].parent
        val origin = frame.parent.toGlobal(frame.position)
        val oldSize = window.size
        window.commandInput.replaceLine("repository \"Мой мир\"")
        for (id in listOf(TuiThemeId.ENDER, TuiThemeId.BTOP, TuiThemeId.MONO)) {
            window.submit("theme ${id.id}")
            awaitUi(gui) { fields[2].text == "Тема: ${id.displayName}" }
            gui.updateScreen()
            val palette = TuiTheme.palette(id)
            assertEquals(oldSize, window.size)
            assertEquals("repository \"Мой мир\"", window.commandInput.text)
            val corner = terminal.getCharacter(origin)
            assertEquals(palette.border, corner.foregroundColor)
            assertEquals(palette.background, corner.backgroundColor)
            val heading = terminal.getCharacter(origin.withRelativeColumn(2))
            assertEquals(palette.title, heading.foregroundColor)
            assertEquals(id != TuiThemeId.MONO, heading.isBold)
            assertEquals(palette.muted, terminal.getCharacter(fields[0].globalPosition).foregroundColor)
            val inputFrame = window.commandInput.parent
            assertEquals(palette.focusBorder, terminal.getCharacter(inputFrame.parent.toGlobal(inputFrame.position)).foregroundColor)
        }
        window.submit("theme ender")
        awaitUi(gui) { fields[2].text == "Тема: Ender / Void" }
        val exit = window.component.descendants().filterIsInstance<TuiButton>().first { it.danger }
        val position = exit.globalPosition.withRelativeColumn(2).withRelativeRow(1)
        gui.handleInput(MouseAction(MouseActionType.MOVE, 0, position))
        gui.updateScreen()
        val caption = terminal.getCharacter(position)
        assertEquals(TuiTheme.palette(TuiThemeId.ENDER).danger, caption.backgroundColor)
        assertTrue(caption.isBold)
        assertSame(window.commandInput, window.focusedInteractable)
        assertFalse(exit.isFocused)
    }

    @Test
    fun `world and friends panels grow in width and height after terminal resize`() = withGui { terminal, screen, gui, window ->
        val panels = window.component.descendants().filterIsInstance<TextBox>().filter { it.isReadOnly }.take(2).toList()
        val previous = panels.map { it.size }
        terminal.setTerminalSize(TerminalSize(140, 48))
        screen.doResizeIfNecessary()
        gui.updateScreen()
        panels.forEachIndexed { index, panel ->
            assertTrue(panel.size.columns > previous[index].columns)
            assertTrue(panel.size.rows > previous[index].rows)
        }
        terminal.setTerminalSize(TerminalSize(80, 24))
        screen.doResizeIfNecessary()
        gui.updateScreen()
        window.component.descendants().filterIsInstance<TuiButton>().forEach { button ->
            assertTrue(button.globalPosition.column + button.size.columns <= 80)
            assertTrue(button.globalPosition.row + button.size.rows <= 24)
        }
    }

    @Test
    fun `mouse click on bordered button fills input and buttons do not show text cursor`() = withGui { terminal, _, gui, window ->
        val button = window.component.descendants().filterIsInstance<TuiButton>().first { it.label == "Хранилище" }
        window.focusedInteractable = button
        gui.updateScreen()
        assertNull(gui.cursorPosition)
        assertTrue(button.size.rows >= 3)
        val frame = terminal.getCharacter(button.globalPosition)
        assertEquals(TextColor.ANSI.BLACK, frame.backgroundColor)
        assertEquals(TextColor.ANSI.WHITE, frame.foregroundColor)
        val caption = terminal.getCharacter(button.globalPosition.withRelativeColumn(2).withRelativeRow(1))
        assertEquals(TextColor.ANSI.WHITE, caption.backgroundColor)
        val position = button.globalPosition.withRelativeColumn(2).withRelativeRow(1)
        gui.handleInput(MouseAction(MouseActionType.CLICK_DOWN, 1, position))
        gui.handleInput(MouseAction(MouseActionType.CLICK_RELEASE, 1, position))
        assertEquals("repository ", window.commandInput.text)
        assertSame(window.commandInput, window.focusedInteractable)
    }

    @Test
    fun `result border survives odd sizes and aligns with command and upper panels`() = withGui { terminal, screen, gui, window ->
        val fields = window.component.descendants().filterIsInstance<TextBox>().filter { it.isReadOnly }.toList()
        val result = fields[2].parent
        val command = window.commandInput.parent
        fields[2].text = "Строка результата\n".repeat(20)
        for ((columns, rows) in listOf(80 to 24, 81 to 25, 100 to 31, 101 to 33, 140 to 48)) {
            terminal.setTerminalSize(TerminalSize(columns, rows))
            screen.doResizeIfNecessary()
            gui.updateScreen()
            assertEquals(command.globalPosition.column, result.globalPosition.column)
            assertEquals(command.size.columns, result.size.columns)
            assertEquals(result.globalPosition.column, fields[0].parent.globalPosition.column)
            val rightEdge = fields[1].parent.globalPosition.column + fields[1].parent.size.columns
            assertEquals(result.globalPosition.column + result.size.columns, rightEdge)
            val origin = result.parent.toGlobal(result.position)
            val bottom = origin.withRelativeRow(result.size.rows - 1)
            assertEquals("└", terminal.getCharacter(bottom).characterString, "${columns}x$rows")
            assertEquals("┘", terminal.getCharacter(bottom.withRelativeColumn(result.size.columns - 1)).characterString)
            for (column in 1 until result.size.columns - 1) {
                assertEquals("─", terminal.getCharacter(bottom.withRelativeColumn(column)).characterString)
            }
            val frame = terminal.getCharacter(command.parent.toGlobal(command.position))
            assertEquals(TextColor.ANSI.BLACK, frame.backgroundColor)
            val firstButton = window.component.descendants().filterIsInstance<TuiButton>().first()
            assertEquals(origin.column, firstButton.globalPosition.column)
        }
    }

    @Test
    fun `F10 and Ctrl Q close from editor and button focus`() {
        withGui { _, _, gui, window ->
            window.focusedInteractable = window.commandInput
            assertTrue(gui.handleInput(KeyStroke(KeyType.F10)))
            assertFalse(gui.windows.contains(window))
        }
        withGui { _, _, gui, window ->
            window.focusedInteractable = window.component.descendants().filterIsInstance<TuiButton>().first()
            assertTrue(gui.handleInput(KeyStroke('q', true, false)))
            assertFalse(gui.windows.contains(window))
        }
    }

    @Test
    fun `exit is not blocked by a running read`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = TuiSession(readWorld = { started.countDown(); release.await(); "world" })
        try {
            withGui(session) { _, _, gui, window ->
                window.submit("repository somewhere")
                assertTrue(started.await(3, TimeUnit.SECONDS))
                gui.handleInput(KeyStroke(KeyType.F10))
                assertFalse(gui.windows.contains(window))
            }
        } finally { release.countDown() }
    }

    private fun Component.descendants(): Sequence<Component> = sequenceOf(this) +
        if (this is Container) childrenList.asSequence().flatMap { it.descendants() } else emptySequence()

    private fun awaitUi(gui: MultiWindowTextGUI, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            gui.guiThread.processEventsAndUpdate()
            Thread.sleep(5)
        }
        assertTrue(ready())
    }

    private fun withGui(session: TuiSession = TuiSession(), test: (DefaultVirtualTerminal, TerminalScreen, MultiWindowTextGUI, TuiWindow) -> Unit) {
        DefaultVirtualTerminal(TerminalSize(100, 32)).use { terminal ->
            TerminalScreen(terminal).use { screen ->
                screen.startScreen()
                val gui = MultiWindowTextGUI(screen).apply { theme = TuiTheme.create() }
                gui.setBlockingIO(false)
                TuiWindow(session).use { window ->
                    gui.addWindow(window)
                    gui.updateScreen()
                    test(terminal, screen, gui, window)
                }
            }
        }
    }
}
