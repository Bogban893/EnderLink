package org.enderlink.cli

import org.enderlink.cli.command.ConnectCommand
import org.enderlink.cli.command.ListenCommand
import org.enderlink.cli.command.SendCommand
import org.enderlink.cli.command.ReceiveCommand
import java.io.IOException
import kotlin.system.exitProcess

private const val HELP = """EnderLink — локальный TCP-чат и передача файлов
Использование:
  listen [--host <адрес>] [--port <порт>]
  connect --host <адрес> [--port <порт>]
  receive --output <каталог> [--host <адрес>] [--port <порт>]
  send --host <адрес> --file <путь> [--port <порт>]

Порт по умолчанию: 25570. listen принимает одного участника.
Для выхода введите /quit или завершите стандартный ввод.
"""

data class CliOptions(
    val command: String, val host: String?, val port: Int,
    val file: String? = null, val output: String? = null,
)

fun parseArguments(args: Array<String>): CliOptions {
    require(args.isNotEmpty() && args[0] in setOf("listen", "connect", "send", "receive")) {
        "Ожидается команда listen, connect, send или receive"
    }
    val allowed = when (args[0]) {
        "send" -> setOf("--host", "--port", "--file")
        "receive" -> setOf("--host", "--port", "--output")
        else -> setOf("--host", "--port")
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val key = args[index]
        require(key in allowed) { "Неизвестный параметр: $key" }
        require(key !in options) { "Параметр указан повторно: $key" }
        require(index + 1 < args.size && !args[index + 1].startsWith("--")) {
            "Не указано значение $key"
        }
        options[key] = args[index + 1]
        index += 2
    }
    val host = options["--host"]
    require(host == null || host.isNotBlank()) { "Адрес не должен быть пустым" }
    require(args[0] !in setOf("connect", "send") || host != null) { "Для ${args[0]} необходим --host" }
    if (args[0] == "send") require(!options["--file"].isNullOrBlank()) { "Необходим --file" }
    if (args[0] == "receive") require(!options["--output"].isNullOrBlank()) { "Необходим --output" }
    val port = options["--port"]?.let {
        requireNotNull(it.toIntOrNull()) { "Порт должен быть целым числом" }
    } ?: 25570
    require(port in 1..65535) { "Порт должен быть в диапазоне 1..65535" }
    return CliOptions(args[0], host, port, options["--file"], options["--output"])
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help")) ||
        args.contentEquals(arrayOf("-h")) ||
        (args.size == 2 && args[0] in setOf("listen", "connect", "send", "receive") && args[1] == "--help")) {
        println(HELP)
        return
    }
    val options = try {
        parseArguments(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка аргументов: ${e.message}\nИспользуйте --help для справки.")
        exitProcess(2)
    }
    try {
        when (options.command) {
            "listen" -> ListenCommand().run(options.host, options.port)
            "connect" -> ConnectCommand().run(requireNotNull(options.host), options.port)
            "send" -> SendCommand().run(requireNotNull(options.host), options.port, requireNotNull(options.file))
            "receive" -> ReceiveCommand().run(options.host, options.port, requireNotNull(options.output))
        }
    } catch (e: IOException) {
        System.err.println("Ошибка ввода-вывода (${e.javaClass.simpleName}): ${e.message}")
        exitProcess(1)
    } catch (e: java.nio.file.InvalidPathException) {
        System.err.println("Некорректный путь: ${e.message}")
        exitProcess(2)
    }
}
