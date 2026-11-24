package org.enderlink.cli.tui

import org.enderlink.network.iroh.IrohProfile
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
    val output: String = "Просмотр миров и друзей. help — команды. Передача в TUI пока не подключена.",
    val close: Boolean = false,
    val error: Boolean = false,
)

/** Read operations are injectable; both buttons and command input use the same session. */
internal class TuiSession(
    private val readWorld: (Path) -> String = ::readWorldStatus,
    private val readProfile: (Path) -> Pair<String, Map<String, String>> = ::readProfileStatus,
) {
    var state = TuiState()
        private set

    /** Called sequentially on the TUI worker, never on its drawing thread. */
    fun execute(line: String): TuiState {
        if (line.isBlank()) return state
        state = state.copy(error = false)
        try {
            val (spec, arguments) = TuiCommands.parse(line)
            state = when (spec.name) {
                "repository" -> {
                    val path = Path.of(arguments.single()).toAbsolutePath().normalize()
                    state.copy(repository = path, world = readWorld(path), output = "Хранилище выбрано: $path")
                }
                "world-status" -> {
                    val path = state.repository ?: throw IllegalArgumentException("Сначала выберите repository <путь>")
                    state.copy(world = readWorld(path), output = "Состояние обновлено. Изменения в Minecraft saves не проверялись.")
                }
                "profile" -> {
                    val path = Path.of(arguments.single()).toAbsolutePath().normalize()
                    require(Files.isRegularFile(path.resolve("secret.key"), NOFOLLOW_LINKS)) { "Выберите существующий профиль с secret.key" }
                    state.copy(profile = path, friends = "Профиль не загружен. Введите friends.", output = "Профиль выбран: $path")
                }
                "friends", "id" -> {
                    val (id, friends) = readProfile(state.profile)
                    state.copy(friends = friends.keys.joinToString("\n") { "$it — доступность неизвестна" }
                        .ifEmpty { "Сохранённых друзей нет." }, output = "Ваш ID: $id\nПрофиль: ${state.profile}")
                }
                "clear" -> state.copy(output = "")
                "exit" -> state.copy(close = true)
                else -> state.copy(output = TuiCommands.all.joinToString("\n") { "${it.usage} — ${it.description}" })
            }
        } catch (error: IOException) {
            state = state.copy(error = true, output = "Ошибка: ${error.message}")
        } catch (error: IllegalArgumentException) {
            state = state.copy(error = true, output = "Ошибка: ${error.message}")
        } catch (error: LinkageError) {
            state = state.copy(error = true, output = "Не удалось загрузить сетевую библиотеку: ${error.message}")
        }
        return state
    }
}

private fun readWorldStatus(path: Path): String {
    // WorldRepository creates metadata during construction: reject arbitrary source/saves folders first.
    require(listOf(".enderlink/objects", ".enderlink/versions", ".enderlink/staging").all {
        Files.isDirectory(path.resolve(it), NOFOLLOW_LINKS)
    }) { "Это не хранилище EnderLink: $path" }
    val repository = WorldRepository(path)
    val current = repository.current() ?: return "В хранилище нет опубликованной версии."
    return "Мир: ${current.worldId}\nВерсия: ${current.versionId}\nРабочая копия: ${repository.workingDirectory}"
}

private fun readProfileStatus(path: Path): Pair<String, Map<String, String>> {
    require(Files.isRegularFile(path.resolve("secret.key"), NOFOLLOW_LINKS)) {
        "Профиль ещё не создан. Создайте его обычной командой cli id или выберите profile <путь>."
    }
    return IrohProfile.open(path).use { it.id to it.friends() }
}
