package org.enderlink.cli.tui

internal data class TuiCommand(val name: String, val argument: String?, val description: String) {
    val usage: String get() = name + (argument?.let { " <$it>" } ?: "")
}

/** One catalog for command validation, completion and help. Backslashes are literal, including Windows paths. */
internal object TuiCommands {
    val all = listOf(
        TuiCommand("help", null, "Справка по интерфейсу"),
        TuiCommand("repository", "путь", "Выбрать существующее хранилище мира"),
        TuiCommand("world-status", null, "Обновить состояние выбранного мира"),
        TuiCommand("profile", "путь", "Выбрать существующий профиль iroh"),
        TuiCommand("friends", null, "Обновить список сохранённых друзей"),
        TuiCommand("id", null, "Показать ID выбранного профиля"),
        TuiCommand("clear", null, "Очистить результат команд"),
        TuiCommand("exit", null, "Закрыть интерфейс"),
    )

    fun parse(line: String): Pair<TuiCommand, List<String>> {
        val tokens = tokenize(line)
        val spec = all.firstOrNull { it.name == tokens.firstOrNull() }
            ?: throw IllegalArgumentException("Неизвестная команда. Введите help или нажмите Tab.")
        require(tokens.size == if (spec.argument == null) 1 else 2) { "Использование: ${spec.usage}" }
        require(tokens.drop(1).all { it.isNotBlank() }) { "Укажите ${spec.argument}" }
        return spec to tokens.drop(1)
    }

    fun completions(line: String): List<String> =
        if (line.any(Char::isWhitespace)) emptyList() else all.map { it.name }.filter { it.startsWith(line) }

    fun hint(line: String): String {
        val name = line.trimStart().takeWhile { !it.isWhitespace() }
        val spec = all.firstOrNull { it.name == name }
        return spec?.let { "${it.usage} — ${it.description}" }
            ?: completions(name).joinToString(" | ").ifEmpty { "Неизвестная команда; help — справка" }
    }

    private fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        for (char in line) {
            when {
                quote != null -> if (char == quote) quote = null else token.append(char)
                char == '"' || char == '\'' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) { result += token.toString(); token.clear(); started = false }
                else -> { token.append(char); started = true }
            }
        }
        require(quote == null) { "Закройте кавычку перед выполнением команды" }
        if (started) result += token.toString()
        return result
    }
}
