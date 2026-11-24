package org.enderlink.cli.tui

import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandInputTest {
    @Test
    fun `completion and history only fill input until explicit execution`() {
        val calls = mutableListOf<String>()
        val input = CommandInput(calls::add)
        input.replaceLine("wor")
        input.handleKeyStroke(KeyStroke(KeyType.Tab))
        assertEquals("world-status ", input.text)
        assertTrue(calls.isEmpty())
        input.handleKeyStroke(KeyStroke(KeyType.Enter))
        assertEquals(listOf("world-status "), calls)
        input.replaceLine("draft")
        input.handleKeyStroke(KeyStroke(KeyType.ArrowUp))
        assertEquals("world-status ", input.text)
        input.handleKeyStroke(KeyStroke(KeyType.ArrowDown))
        assertEquals("draft", input.text)
        input.handleKeyStroke(KeyStroke('c', true, false))
        assertEquals("", input.text)
        assertEquals(1, calls.size)
        input.replaceLine("repository \"unfinished")
        input.handleKeyStroke(KeyStroke(KeyType.Enter))
        assertEquals("repository \"unfinished", input.text)
    }
}
