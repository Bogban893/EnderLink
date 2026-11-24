package org.enderlink.cli.tui

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiCompletionsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `paths with spaces quotes and Cyrillic complete without execution or shell escaping`() {
        val path = Files.createDirectory(root.resolve("Мой 'новый' \"мир\""))
        Files.writeString(root.resolve("Мой файл"), "ignored")
        val line = "repository \"$root/Мой"
        val completed = TuiCompletions.complete(line, TuiState()).single()
        assertEquals(listOf(path.toString() + "/"), TuiCommands.parse(completed).second)
        val input = CommandInput { error("completion must not execute") }
        input.applyCompletion(listOf(completed))
        assertEquals(completed, input.text)
        assertEquals(listOf(path.toString()), TuiWords.scan(TuiWords.quote(path.toString())).map { it.text })
    }

    @Test
    fun `peer version and option completions use selected state`() {
        val state = TuiState(friendNames = listOf("alex", "anna"), versionIds = listOf("sha256:123"))
        assertEquals(listOf("world-send --peer \"alex\""), TuiCompletions.complete("world-send --peer al", state))
        assertEquals(listOf("remove \"alex\""), TuiCompletions.complete("remove al", state))
        assertEquals(listOf("world-checkout --version \"sha256:123\""), TuiCompletions.complete("world-checkout --version sha", state))
        assertTrue(TuiCompletions.complete("world-send --p", state).contains("world-send --peer"))
        assertEquals(listOf("theme ender"), TuiCompletions.complete("theme en", state))
    }
}
