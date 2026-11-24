package org.enderlink.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Timeout(15)
class TuiApplicationTest {
    @Test
    fun `terminal is restored and closed when startup fails`() {
        val terminal = object : DefaultVirtualTerminal(TerminalSize(80, 24)) {
            var privateMode = false
            var closed = false
            override fun enterPrivateMode() { privateMode = true; super.enterPrivateMode() }
            override fun exitPrivateMode() { privateMode = false; super.exitPrivateMode() }
            override fun close() { closed = true; super.close() }
            override fun flush() { throw java.io.IOException("terminal failure") }
        }
        assertFailsWith<java.io.IOException> { TuiApplication.run(terminal) }
        assertFalse(terminal.privateMode)
        assertTrue(terminal.closed)
    }

    @Test
    fun `virtual terminal renders commands and restores terminal on exit`() {
        val rendered = CountDownLatch(1)
        val terminal = object : DefaultVirtualTerminal(TerminalSize(100, 32)) {
            var privateMode = false
            var closed = false
            override fun enterPrivateMode() { privateMode = true; super.enterPrivateMode() }
            override fun exitPrivateMode() { privateMode = false; super.exitPrivateMode() }
            override fun close() { closed = true; super.close() }
            override fun flush() {
                super.flush()
                val text = buildString {
                    for (row in 0 until terminalSize.rows) {
                        for (column in 0 until terminalSize.columns) append(getCharacter(column, row).characterString)
                        append('\n')
                    }
                }
                if (text.contains("Справка по интерфейсу")) rendered.countDown()
            }
        }
        "help".forEach { terminal.addInput(KeyStroke(it, false, false)) }
        terminal.addInput(KeyStroke(KeyType.Enter))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val run = pool.submit { TuiApplication.run(terminal) }
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            terminal.addInput(KeyStroke(KeyType.F10))
            run.get(5, TimeUnit.SECONDS)
            assertFalse(terminal.privateMode)
            assertTrue(terminal.closed)
        } finally {
            terminal.addInput(KeyStroke(KeyType.F10))
            pool.shutdownNow()
        }
    }
}
