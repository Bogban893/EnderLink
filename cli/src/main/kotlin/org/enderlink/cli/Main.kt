package org.enderlink.cli

import org.enderlink.cli.command.ConnectCommand
import org.enderlink.cli.command.ListenCommand
import java.io.IOException
import kotlin.system.exitProcess

private const val HELP = """EnderLink — локальный TCP-чат
Использование:
  listen [--host <адрес>] [--port <порт>]
  connect --host <адрес> [--port <порт>]

Порт по умолчанию: 25570. listen принимает одного участника.
Для выхода введите /quit или завершите стандартный ввод.
"""

data class CliOptions(val command: String, val host: String?, val port: Int)

fun parseArguments(args: Array<String>): CliOptions {
    require(args.isNotEmpty() && args[0] in setOf("listen", "connect")) {
        "Ожидается команда listen или connect"
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val key = args[index]
        require(key in setOf("--host", "--port")) { "Неизвестный параметр: $key" }
        require(key !in options) { "Параметр указан повторно: $key" }
        require(index + 1 < args.size && !args[index + 1].startsWith("--")) {
            "Не указано значение $key"
        }
        options[key] = args[index + 1]
        index += 2
    }
    val host = options["--host"]
    require(host == null || host.isNotBlank()) { "Адрес не должен быть пустым" }
    require(args[0] != "connect" || host != null) { "Для connect необходим --host" }
    val port = options["--port"]?.let {
        requireNotNull(it.toIntOrNull()) { "Порт должен быть целым числом" }
    } ?: 25570
    require(port in 1..65535) { "Порт должен быть в диапазоне 1..65535" }
    return CliOptions(args[0], host, port)
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help")) ||
        args.contentEquals(arrayOf("-h")) ||
        (args.size == 2 && args[0] in setOf("listen", "connect") && args[1] == "--help")) {
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
        }
    } catch (e: IOException) {
        System.err.println("Сетевая ошибка (${e.javaClass.simpleName}): ${e.message}")
        exitProcess(1)
    }
}
