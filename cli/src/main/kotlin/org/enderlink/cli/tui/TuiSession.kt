package org.enderlink.cli.tui

import org.enderlink.network.iroh.IrohProfile
import org.enderlink.cli.CliOptions
import org.enderlink.cli.command.OperationControl
import org.enderlink.cli.command.ProfileCommand
import org.enderlink.world.WorldRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class TuiState(
    val repository: Path? = null,
    val world: String = "Хранилище не выбрано. Введите repository <путь>.",
    val profile: Path = IrohProfile.defaultDirectory(),
    val friends: String = "Профиль не загружен. Нажмите «Друзья» или введите friends.",
    val output: String = "help — команды. Публикуйте только закрытый мир; TCP используйте в доверенной сети.",
    val close: Boolean = false,
    val error: Boolean = false,
    val cancelled: Boolean = false,
    val theme: TuiThemeId = TuiThemeId.MONO,
    val friendsLoaded: Boolean = false,
    val friendNames: List<String> = emptyList(),
    val versionIds: List<String> = emptyList(),
)

/** Read operations are injectable; both buttons and command input use the same session. */
internal class TuiSession(
    private val readWorld: (Path) -> String = ::readWorldStatus,
    private val readProfile: (Path) -> Pair<String, Map<String, String>> = ::readProfileStatus,
    private val themeSettings: TuiThemeSettings? = null,
    private val operations: TuiOperations = TuiOperations(),
    private val changeProfile: (CliOptions, (String) -> Unit) -> Pair<String, Map<String, String>> = { options, report ->
        ProfileCommand(report).run(options)
    },
) {
    var state = TuiState(theme = themeSettings?.load() ?: TuiThemeId.MONO)
        private set

    /** Called sequentially on the TUI worker, never on its drawing thread. */
    fun execute(line: String, onOutput: (String) -> Unit = {}, control: OperationControl = OperationControl()): TuiState {
        if (line.isBlank()) return state
        state = state.copy(error = false, cancelled = false)
        val lines = ArrayDeque<String>()
        val report: (String) -> Unit = { message ->
            val snapshot = synchronized(lines) {
                lines.addLast(message.take(4096))
                while (lines.size > 100) lines.removeFirst()
                lines.joinToString("\n")
            }
            onOutput(snapshot)
        }
        try {
            val (spec, arguments) = TuiCommands.parse(line)
            state = when (spec.name) {
                "repository" -> {
                    val path = Path.of(arguments.single()).toAbsolutePath().normalize()
                    val description = readWorld(path)
                    state.copy(repository = path, world = description, versionIds = emptyList(), output = "Хранилище выбрано: $path")
                }
                "world-status" -> {
                    val path = state.repository ?: throw IllegalArgumentException("Сначала выберите repository <путь>")
                    state.copy(world = readWorld(path), output = "Состояние обновлено. Изменения в Minecraft saves не проверялись.")
                }
                "profile" -> {
                    val path = Path.of(arguments.single()).toAbsolutePath().normalize()
                    require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path.resolve("secret.key"), NOFOLLOW_LINKS)) { "Выберите существующий профиль с secret.key или новый каталог" }
                    state.copy(profile = path, friendsLoaded = false, friendNames = emptyList(), friends = "Профиль не загружен. Введите friends; для нового профиля — profile-init.", output = "Профиль выбран: $path")
                }
                "friends", "id" -> {
                    val (id, friends) = readProfile(state.profile)
                    state.copy(friendsLoaded = true, friendNames = friends.keys.toList(), friends = friends.keys.joinToString("\n") { "$it — доступность неизвестна" }
                        .ifEmpty { "Сохранённых друзей нет." }, output = "Ваш ID: $id\nПрофиль: ${state.profile}")
                }
                "profile-init", "add", "remove" -> {
                    if (spec.name != "profile-init") requireExistingProfile(state.profile)
                    control.protectCommit()
                    val options = CliOptions(if (spec.name == "profile-init") "id" else spec.name, null, 25570,
                        profile = state.profile.toString(), friendName = arguments.firstOrNull(), friendId = arguments.getOrNull(1))
                    val (id, friends) = changeProfile(options, report)
                    state.copy(friendsLoaded = true, friendNames = friends.keys.toList(),
                        friends = friends.keys.joinToString("\n") { "$it — доступность неизвестна" }.ifEmpty { "Сохранённых друзей нет." },
                        output = lines.joinToString("\n") + "\nВаш ID: $id")
                }
                "world-publish", "world-versions", "world-checkout", "world-import-owner", "world-send", "world-receive" -> {
                    val options = operations.options(spec.name, arguments, state.repository, state.profile)
                    val path = Path.of(requireNotNull(options.repository)).toAbsolutePath().normalize()
                    if (options.iroh) requireExistingProfile(Path.of(requireNotNull(options.profile)))
                    if (spec.name !in setOf("world-publish", "world-receive") || !isUnusedRepositoryPath(path)) requireRepository(path)
                    operations.execute(options, report, control)
                    val repository = WorldRepository(path)
                    state.copy(repository = path, world = readWorld(path), versionIds = repository.versions().map { it.versionId },
                        output = synchronized(lines) { lines.joinToString("\n") })
                }
                "cancel" -> state.copy(output = "Нет выполняющейся операции.")
                "theme" -> {
                    if (arguments.isEmpty()) state.copy(output = "Текущая тема: ${state.theme.displayName}\n" +
                        TuiThemeId.entries.joinToString("\n") { "theme ${it.id} — ${it.displayName}" })
                    else {
                        val theme = requireNotNull(TuiThemeId.fromId(arguments.single())) { "Темы: mono, ender, btop" }
                        themeSettings?.save(theme)
                        state.copy(theme = theme, output = "Тема: ${theme.displayName}")
                    }
                }
                "clear" -> state.copy(output = "")
                "exit" -> state.copy(close = true)
                else -> state.copy(output = TuiCommands.all.joinToString("\n") { "${it.usage} — ${it.description}" })
            }
        } catch (error: IOException) {
            val detail = if (control.isCancelled) "Операция отменена. Проверенные блоки сохранены для докачки.\n" +
                "Если передача уже завершалась, проверьте версию у получателя или повторите отправку." else "Ошибка: ${error.message}"
            val previous = synchronized(lines) { lines.joinToString("\n") }
            state = state.copy(error = !control.isCancelled, cancelled = control.isCancelled,
                output = if (previous.isEmpty()) detail else "$previous\n$detail")
        } catch (error: IllegalArgumentException) {
            state = state.copy(error = true, output = "Ошибка: ${error.message}")
        } catch (error: LinkageError) {
            state = state.copy(error = true, output = "Не удалось загрузить сетевую библиотеку: ${error.message}")
        }
        return state
    }
}

private fun readWorldStatus(path: Path): String {
    if (isUnusedRepositoryPath(path)) return "Новое хранилище. Создастся при публикации или приёме."
    requireRepository(path)
    val repository = WorldRepository(path)
    val current = repository.current() ?: return "В хранилище нет опубликованной версии."
    return "Мир: ${current.worldId}\nВерсия: ${current.versionId}\nРабочая копия: ${repository.workingDirectory}"
}

private fun requireRepository(path: Path) {
    // WorldRepository creates metadata during construction: reject arbitrary source/saves folders first.
    require(listOf(".enderlink/objects", ".enderlink/versions", ".enderlink/staging").all {
        Files.isDirectory(path.resolve(it), NOFOLLOW_LINKS)
    }) { "Это не хранилище EnderLink: $path" }
}

private fun isUnusedRepositoryPath(path: Path): Boolean = !Files.exists(path, NOFOLLOW_LINKS) ||
    Files.isDirectory(path, NOFOLLOW_LINKS) && Files.newDirectoryStream(path).use { !it.iterator().hasNext() }

private fun readProfileStatus(path: Path): Pair<String, Map<String, String>> {
    requireExistingProfile(path)
    return ProfileCommand {}.run(CliOptions("friends", null, 25570, profile = path.toString()))
}

private fun requireExistingProfile(path: Path) = require(Files.isRegularFile(path.resolve("secret.key"), NOFOLLOW_LINKS)) {
    "Профиль ещё не создан. Введите profile-init или выберите profile <путь>."
}
