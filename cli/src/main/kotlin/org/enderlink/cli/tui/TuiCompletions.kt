package org.enderlink.cli.tui

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Directory reads run on the session worker, not the drawing/input thread. */
internal object TuiCompletions {
    private val flags = mapOf(
        "world-publish" to listOf("--source", "--repository", "--closed"),
        "world-send" to listOf("--peer", "--host", "--port", "--relay", "--source", "--closed", "--repository", "--profile"),
        "world-receive" to listOf("--iroh", "--peer", "--host", "--port", "--repository", "--profile"),
        "world-checkout" to listOf("--version", "--repository"),
        "world-import-owner" to listOf("--owner-uuid", "--repository"),
    )

    fun complete(line: String, state: TuiState): List<String> {
        val words = TuiWords.scan(line, incomplete = true)
        if (words.isEmpty() || words.size == 1 && words[0].end == line.length) return TuiCommands.completions(line)
        val last = words.lastOrNull()?.takeIf { it.end == line.length }
        val prefix = last?.text.orEmpty()
        val before = if (last == null) words else words.dropLast(1)
        val command = words.first().text
        val previous = before.lastOrNull()?.text
        val start = last?.start ?: line.length
        fun replace(values: List<String>) = values.filter { it.startsWith(prefix) }.map { line.take(start) + TuiWords.quote(it) }
        if (previous in setOf("--source", "--repository", "--profile") || command in setOf("repository", "profile") && before.size == 1) {
            val path = Path.of(prefix.ifEmpty { "." })
            val parent = if (prefix.isEmpty() || prefix.endsWith('/') || prefix.endsWith('\\')) path else path.parent ?: Path.of(".")
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return emptyList()
            val name = if (parent == path) "" else path.fileName.toString()
            return Files.newDirectoryStream(parent).use { entries ->
                entries.asSequence().filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().startsWith(name) }
                    .take(100).map { entry ->
                        val value = if (parent == Path.of(".") && !prefix.startsWith("./")) entry.fileName.toString() else entry.toString()
                        line.take(start) + TuiWords.quote(value + "/")
                    }.sorted().toList()
            }
        }
        if (previous == "--peer" || command == "remove" && before.size == 1) return replace(state.friendNames)
        if (previous == "--version") return replace(state.versionIds)
        if (command == "theme") return TuiCommands.completions(line)
        return flags[command].orEmpty().filter { it.startsWith(prefix) && before.none { word -> word.text == it } }
            .map { line.take(start) + it }
    }
}
