package org.enderlink.cli.tui

internal data class TuiCommand(
    val name: String, val argument: String?, val description: String, val optional: Boolean = false,
    val minArguments: Int = if (argument == null || optional) 0 else 1,
    val maxArguments: Int = if (argument == null) 0 else 1,
) {
    val usage: String get() = name + (argument?.let {
        if (optional) " [$it]" else if (it.startsWith("--")) " $it" else " <$it>"
    } ?: "")
}

/** One catalog for command validation, completion and help. Backslashes are literal, including Windows paths. */
internal object TuiCommands {
    val all = listOf(
        TuiCommand("help", null, "Справка по интерфейсу"),
        TuiCommand("repository", "путь", "Выбрать существующее или новое хранилище мира"),
        TuiCommand("world-status", null, "Обновить состояние выбранного мира"),
        TuiCommand("profile", "путь", "Выбрать существующий или новый каталог профиля iroh"),
        TuiCommand("friends", null, "Обновить список сохранённых друзей"),
        TuiCommand("id", null, "Показать ID выбранного профиля"),
        TuiCommand("profile-init", null, "Создать профиль в выбранном каталоге (явное создание ID)"),
        TuiCommand("add", "имя ID", "Добавить друга", minArguments = 2, maxArguments = 2),
        TuiCommand("remove", "имя", "Удалить друга"),
        TuiCommand("world-publish", "--source путь --closed [--repository путь]", "Опубликовать закрытый мир", minArguments = 1, maxArguments = Int.MAX_VALUE),
        TuiCommand("world-versions", null, "Показать известные версии выбранного мира"),
        TuiCommand("world-checkout", "--version ID", "Явно выбрать историческую ветку", maxArguments = Int.MAX_VALUE),
        TuiCommand("world-import-owner", "--owner-uuid UUID", "Импортировать владельца старого мира", maxArguments = Int.MAX_VALUE),
        TuiCommand("world-send", "--peer имя | --host адрес [параметры CLI]", "Отправить мир; --source требует --closed", maxArguments = Int.MAX_VALUE),
        TuiCommand("world-receive", "--iroh | --host адрес [параметры CLI]", "Принять мир; TCP также работает без параметров", optional = true, minArguments = 0, maxArguments = Int.MAX_VALUE),
        TuiCommand("cancel", null, "Отменить ожидание или передачу"),
        TuiCommand("theme", "mono|ender|btop", "Выбрать тему оформления", optional = true),
        TuiCommand("clear", null, "Очистить результат команд"),
        TuiCommand("exit", null, "Закрыть интерфейс"),
    )

    fun parse(line: String): Pair<TuiCommand, List<String>> {
        val tokens = TuiWords.scan(line).map { it.text }
        val spec = all.firstOrNull { it.name == tokens.firstOrNull() }
            ?: throw IllegalArgumentException("Неизвестная команда. Введите help или нажмите Tab.")
        require(tokens.size - 1 in spec.minArguments..spec.maxArguments) { "Использование: ${spec.usage}" }
        require(tokens.drop(1).all { it.isNotBlank() }) { "Укажите ${spec.argument}" }
        if (spec.name.startsWith("world-") && spec.name != "world-status") {
            TuiOperations().options(spec.name, tokens.drop(1), java.nio.file.Path.of("."), java.nio.file.Path.of("."))
        }
        return spec to tokens.drop(1)
    }

    fun completions(line: String): List<String> {
        if (line.startsWith("theme ")) {
            val prefix = line.removePrefix("theme ").trimStart()
            return TuiThemeId.entries.map { "theme ${it.id}" }.filter { it.startsWith("theme $prefix") }
        }
        return if (line.any(Char::isWhitespace)) emptyList() else all.map { it.name }.filter { it.startsWith(line) }
    }

    fun hint(line: String): String {
        val name = line.trimStart().takeWhile { !it.isWhitespace() }
        val spec = all.firstOrNull { it.name == name }
        return spec?.let {
            val error = if (line.trim() == name) null else runCatching { parse(line) }.exceptionOrNull()?.message
            "${it.usage} — ${error ?: it.description}"
        }
            ?: completions(name).joinToString(" | ").ifEmpty { "Неизвестная команда; help — справка" }
    }

}

internal object TuiWords {
    data class Word(val text: String, val start: Int, val end: Int)

    fun quote(value: String) = "\"" + value.replace("\"", "\"'\"'\"") + "\""

    fun scan(line: String, incomplete: Boolean = false): List<Word> {
        val result = mutableListOf<Word>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        var start = 0
        for ((index, char) in line.withIndex()) {
            if (!started && !char.isWhitespace()) start = index
            when {
                quote != null -> if (char == quote) quote = null else token.append(char)
                char == '"' || char == '\'' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) { result += Word(token.toString(), start, index); token.clear(); started = false }
                else -> { token.append(char); started = true }
            }
        }
        require(incomplete || quote == null) { "Закройте кавычку перед выполнением команды" }
        if (started) result += Word(token.toString(), start, line.length)
        return result
    }
}
