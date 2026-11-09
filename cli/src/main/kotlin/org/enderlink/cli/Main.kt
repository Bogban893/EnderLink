package org.enderlink.cli

import org.enderlink.cli.command.ConnectCommand
import org.enderlink.cli.command.ListenCommand
import org.enderlink.cli.command.SendCommand
import org.enderlink.cli.command.ReceiveCommand
import org.enderlink.cli.command.WorldCommand
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.validateRelay
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

private val networkCommands = setOf("listen", "connect", "send", "receive", "world-send", "world-receive")
private val profileCommands = setOf("id", "add", "friends", "remove")
private val localWorldCommands = setOf("world-publish", "world-status")
private val commands = networkCommands + profileCommands + localWorldCommands
private const val HELP = """EnderLink — чат, файлы и версии мира по TCP или через интернет (iroh)
TCP:
  listen [--host <адрес>] [--port <порт>]
  connect --host <адрес> [--port <порт>]
  receive --output <каталог> [--host <адрес>] [--port <порт>]
  send --host <адрес> (--file <путь> | --directory <каталог>) [--port <порт>]
  world-publish --source <закрытый мир> --repository <каталог>
  world-status --repository <каталог>
  world-receive --repository <каталог> [--host <адрес>] [--port <порт>]
  world-send --repository <каталог> --host <адрес> [--port <порт>]
Интернет:
  id | friends
  add <имя> <ID> | remove <имя>
  listen --iroh [--peer <имя>]
  connect --peer <имя> [--relay <https://адрес>]
  receive --iroh --output <каталог> [--peer <имя>]
  send --peer <имя> (--file <путь> | --directory <каталог>) [--relay <https://адрес>]
  world-receive --repository <каталог> --iroh [--peer <имя>]
  world-send --repository <каталог> --peer <имя> [--relay <https://адрес>]

Для iroh: --profile <каталог>, --debug (сеть). Профиль по умолчанию: ~/.enderlink/iroh-test.
Оба участника один раз добавляют ID друг друга. Relay после успеха запоминается.
TCP-порт по умолчанию: 25570. Каждая команда обслуживает одно соединение.
Для выхода из чата: /quit или конец стандартного ввода.
"""

data class CliOptions(
    val command: String, val host: String?, val port: Int,
    val file: String? = null, val output: String? = null, val directory: String? = null,
    val iroh: Boolean = false, val peer: String? = null, val profile: String? = null,
    val relay: String? = null, val debug: Boolean = false,
    val friendName: String? = null, val friendId: String? = null,
    val repository: String? = null, val source: String? = null,
)

fun parseArguments(args: Array<String>): CliOptions {
    require(args.isNotEmpty() && args[0] in commands) { "Неизвестная команда" }
    val command = args[0]
    val positionalCount = when (command) { "add" -> 2; "remove" -> 1; else -> 0 }
    require(args.size > positionalCount) { "Недостаточно аргументов $command" }
    val positional = args.drop(1).take(positionalCount)
    require(positional.all { it.isNotBlank() && !it.startsWith("--") }) { "Укажите имя и ID друга для add, имя для remove" }
    val allowed = if (command in profileCommands) setOf("--profile") else if (command in localWorldCommands) {
        if (command == "world-publish") setOf("--repository", "--source") else setOf("--repository")
    } else {
        val base = setOf("--host", "--port", "--iroh", "--peer", "--profile", "--debug")
        when (command) {
            "send" -> base + setOf("--file", "--directory", "--relay")
            "receive" -> base + "--output"
            "connect" -> base + "--relay"
            "world-send" -> base + setOf("--repository", "--relay")
            "world-receive" -> base + "--repository"
            else -> base
        }
    }
    val options = mutableMapOf<String, String>()
    var index = positionalCount + 1
    while (index < args.size) {
        val key = args[index]
        require(key in allowed) { "Неизвестный параметр: $key" }
        require(key !in options) { "Параметр указан повторно: $key" }
        if (key in setOf("--iroh", "--debug")) { options[key] = "true"; index++; continue }
        require(index + 1 < args.size && !args[index + 1].startsWith("--")) { "Не указано значение $key" }
        options[key] = args[index + 1]
        index += 2
    }
    val host = options["--host"]
    val peer = options["--peer"]
    val profile = options["--profile"]
    require(host == null || host.isNotBlank()) { "Адрес не должен быть пустым" }
    require(peer == null || peer.isNotBlank()) { "Имя друга не должно быть пустым" }
    require(profile == null || profile.isNotBlank()) { "Путь профиля не должен быть пустым" }
    val iroh = "--iroh" in options || peer != null
    if (iroh) {
        require(host == null && "--port" !in options) { "Для iroh используйте --peer, без --host и --port" }
        require(command !in setOf("connect", "send", "world-send") || peer != null) { "Для iroh необходим --peer <имя>" }
    } else if (command in networkCommands) {
        require(command !in setOf("connect", "send", "world-send") || host != null) { "Для $command необходим --host или --peer" }
        require(profile == null && "--relay" !in options && "--debug" !in options) { "--profile, --relay и --debug используются с iroh" }
    }
    options["--relay"]?.let(::validateRelay)
    if (command == "send") {
        require(("--file" in options) xor ("--directory" in options)) { "Укажите ровно один параметр: --file или --directory" }
        require(!(options["--file"] ?: options["--directory"]).isNullOrBlank()) { "Путь не должен быть пустым" }
    }
    if (command == "receive") require(!options["--output"].isNullOrBlank()) { "Необходим --output" }
    if (command.startsWith("world-")) require(!options["--repository"].isNullOrBlank()) { "Необходим --repository" }
    if (command == "world-publish") require(!options["--source"].isNullOrBlank()) { "Необходим --source" }
    val port = options["--port"]?.let { requireNotNull(it.toIntOrNull()) { "Порт должен быть целым числом" } } ?: 25570
    require(port in 1..65535) { "Порт должен быть в диапазоне 1..65535" }
    return CliOptions(command, host, port, options["--file"], options["--output"], options["--directory"],
        iroh, peer, profile, options["--relay"], "--debug" in options, positional.getOrNull(0), positional.getOrNull(1),
        options["--repository"], options["--source"])
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h")) ||
        (args.size == 2 && args[0] in commands && args[1] == "--help")) {
        println(HELP); return
    }
    try {
        val options = parseArguments(args)
        when (options.command) {
            "listen" -> ListenCommand().run(options)
            "connect" -> ConnectCommand().run(options)
            "send" -> SendCommand().run(options)
            "receive" -> ReceiveCommand().run(options)
            "world-publish", "world-status", "world-send", "world-receive" -> WorldCommand().run(options)
            else -> IrohProfile.open(options.profile?.let(Path::of) ?: IrohProfile.defaultDirectory()).use { profile ->
                when (options.command) {
                    "id" -> println("Ваш ID: ${profile.id}")
                    "friends" -> {
                        val friends = profile.friends()
                        if (friends.isEmpty()) println("Друзей пока нет. Используйте add <имя> <ID>.")
                        friends.forEach { (name, id) -> println("$name $id") }
                    }
                    "add" -> { profile.add(requireNotNull(options.friendName), requireNotNull(options.friendId)); println("Друг сохранён.") }
                    "remove" -> { profile.remove(requireNotNull(options.friendName)); println("Друг удалён.") }
                }
            }
        }
    } catch (e: java.nio.file.InvalidPathException) {
        System.err.println("Некорректный путь: ${e.message}"); exitProcess(2)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка аргументов: ${e.message}\nИспользуйте --help для справки."); exitProcess(2)
    } catch (e: IOException) {
        System.err.println("Ошибка ввода-вывода (${e.javaClass.simpleName}): ${e.message}"); exitProcess(1)
    } catch (e: LinkageError) {
        System.err.println("Не удалось загрузить iroh: ${e.message}"); exitProcess(1)
    }
}
