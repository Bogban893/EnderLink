package org.enderlink.irohtest

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohException
import computer.iroh.LogLevel
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import computer.iroh.presetN0
import computer.iroh.setLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val HELP = """EnderLink — минимальный iroh-чат с постоянным ID
  id                         Показать свой ID
  add <имя> <ID>             Запомнить друга
  friends                    Показать друзей
  remove <имя>               Удалить друга
  listen                     Ждать одного сохранённого друга
  connect <имя>              Подключиться к сохранённому другу

Для каждой команды: --profile <каталог> (по умолчанию ~/.enderlink/iroh-test).
Для listen/connect: --debug — подробные сетевые логи.
Для connect: --relay <https://адрес> — relay друга, показанный его командой listen.
Добавьте ID друг друга на обоих компьютерах. Один запускает listen, другой connect.
В чате: /probe — проверка данных, /status — текущий путь, /quit — выход.
Используются публичные поиск и relay iroh; собственный сервер не требуется.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) { println(HELP); return }
    try {
        val options = parseArguments(args.toList())
        val command = options.command
        if (options.debug) setLogLevel(LogLevel.DEBUG)
        Profile.open(options.directory).use { profile ->
            when (command[0]) {
                "id" -> println("Ваш ID: ${profile.id}")
                "friends" -> {
                    val friends = profile.friends()
                    if (friends.isEmpty()) println("Друзей пока нет. Используйте add <имя> <ID>.")
                    else friends.forEach { (name, id) -> println("$name $id") }
                }
                "add" -> { profile.add(command[1], command[2]); println("Друг ${command[1]} сохранён.") }
                "remove" -> { profile.remove(command[1]); println("Друг ${command[1]} удалён.") }
                else -> runBlocking { startChat(profile, options) }
            }
        }
    } catch (e: Exception) {
        val message = if (e is IrohException) e.message() else e.message
        System.err.println("Ошибка: ${message ?: e.javaClass.simpleName}")
        exitProcess(1)
    } catch (e: LinkageError) {
        System.err.println("Не удалось загрузить нативную библиотеку iroh: ${e.message}")
        exitProcess(1)
    }
}

private data class Options(val directory: Path, val command: List<String>, val debug: Boolean, val relay: String?)

private fun parseArguments(args: List<String>): Options {
    var directory = Path.of(System.getProperty("user.home"), ".enderlink", "iroh-test")
    val command = mutableListOf<String>()
    var profileSpecified = false
    var debug = false
    var relay: String? = null
    var index = 0
    while (index < args.size) {
        if (args[index] == "--debug") {
            require(!debug) { "--debug указан повторно" }
            debug = true; index++
        } else if (args[index] == "--relay") {
            require(relay == null && index + 1 < args.size) { "Укажите --relay <https://адрес> один раз" }
            relay = args[index + 1]
            val uri = URI(relay)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Relay должен быть HTTPS-адресом из вывода listen"
            }
            index += 2
        } else if (args[index] == "--profile") {
            require(!profileSpecified && index + 1 < args.size && args[index + 1].isNotBlank()) { "Укажите --profile <каталог> один раз" }
            directory = Path.of(args[index + 1]); profileSpecified = true; index += 2
        } else { command.add(args[index]); index++ }
    }
    val size = when (command.firstOrNull()) {
        "id", "friends", "listen" -> 1
        "connect", "remove" -> 2
        "add" -> 3
        else -> throw IllegalArgumentException(HELP)
    }
    require(command.size == size) { HELP }
    require(!debug || command[0] in setOf("listen", "connect")) { "--debug используется с listen или connect" }
    require(relay == null || command[0] == "connect") { "--relay используется только с connect и задаёт relay друга" }
    return Options(directory, command, debug, relay)
}

private suspend fun startChat(profile: Profile, options: Options) {
    val command = options.command
    val target = if (command[0] == "connect") profile.friendId(command[1]) else null
    require(target != null || profile.friends().isNotEmpty()) { "Сначала добавьте друга командой add <имя> <ID>" }
    println("Ваш ID: ${profile.id}")
    println("Подключение к публичной сети iroh…")
    val endpoint = Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = profile.key, alpns = listOf(ALPN)))
    try {
        withTimeout(60_000) { endpoint.online() }
        println("Узел онлайн. ID сохраняется после перезапуска и смены сети.")
        endpoint.addr().use { addr ->
            println("Мой relay: ${addr.relayUrl() ?: "пока не определён"}")
        }
        val connection = if (target == null) {
            println("Ожидание сохранённого друга…")
            acceptFriend(endpoint, profile, ::println)
        } else {
            println("Поиск ${command[1]} по постоянному ID…")
            println("ID друга: $target")
            if (options.relay != null) println("Relay друга задан явно: ${options.relay}")
            EndpointId.fromString(target).use { id ->
                EndpointAddr(id, options.relay, emptyList()).use { addr ->
                    try {
                        withTimeout(60_000) { endpoint.connect(addr, ALPN) }
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        endpoint.remoteAddr(id)?.use { known ->
                            println("Известный relay друга: ${known.relayUrl() ?: "нет"}")
                            println("Известных прямых адресов друга: ${known.directAddresses().size}")
                        } ?: println("Адрес друга не появился в локальном кеше поиска.")
                        val reason = if (e is IrohException) e.debugMessage() else e.message
                        throw java.io.IOException("Подключение к ${command[1]} ($target) не удалось: $reason. " +
                            "Проверьте ID и оба работающих процесса. Для диагностики повторите с --debug; " +
                            "relay из вывода listen можно передать через --relay.", e)
                    }
                }
            }
        }
        connection.use {
            val lastRoute = AtomicReference<String?>(null)
            fun reportPath(paths: List<PathSnapshot>) {
                val route = paths.filter { it.isSelected }.joinToString { "${it.id}:${it.remoteAddr}:${it.isRelay}" }
                if (lastRoute.getAndSet(route) != route) println(describePaths(paths))
            }
            val watcher = connection.watchPaths(object : PathChangeCallback {
                override suspend fun onChange(paths: List<PathSnapshot>) { reportPath(paths) }
            })
            try {
                reportPath(connection.paths())
                IrohChat.open(connection, target != null).use { stream ->
                    println("Чат открыт. Текст, /probe, /status, /quit.")
                    chat(connection, stream)
                }
            } finally {
                watcher.stop(); watcher.close()
                connection.close(0L, "bye".toByteArray())
            }
        }
    } finally {
        withContext(NonCancellable) {
            try { withTimeout(10_000) { endpoint.shutdown() } } finally { endpoint.close() }
        }
    }
}

private suspend fun chat(connection: Connection, stream: IrohChat) = coroutineScope {
    val lines = Channel<String>(16)
    val probes = ConcurrentHashMap.newKeySet<String>()
    thread(name = "iroh-console", isDaemon = true) {
        try {
            while (true) {
                val line = readlnOrNull() ?: break
                runBlocking { lines.send(line) }
                if (line == "/quit") break
            }
        } catch (_: CancellationException) {
            // Получатель завершился, терминальный поток больше не нужен.
        } finally { lines.close() }
    }
    val sender = launch {
        for (line in lines) {
            when (line) {
                "/quit" -> break
                "/status" -> println(describePaths(connection.paths()))
                "/probe" -> {
                    val bytes = ByteArray(32 * 1024).also(SecureRandom()::nextBytes)
                    probes.add(sha256(bytes))
                    stream.send(byteArrayOf(1) + bytes)
                    println("Проверка отправлена: ${bytes.size} байт")
                }
                else -> {
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    if (bytes.size >= IrohChat.MAX_MESSAGE) println("Сообщение слишком длинное")
                    else stream.send(byteArrayOf(0) + bytes)
                }
            }
        }
        connection.close(0L, "bye".toByteArray())
    }
    try {
        while (true) {
            val bytes = stream.receive() ?: break
            require(bytes.isNotEmpty()) { "Пустое сообщение протокола" }
            when (bytes[0].toInt()) {
                0 -> println("Собеседник: ${bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)}")
                1 -> {
                    val hash = sha256(bytes.copyOfRange(1, bytes.size))
                    stream.send(byteArrayOf(2) + hash.toByteArray(Charsets.US_ASCII))
                    println("Проверено ${bytes.size - 1} байт, SHA-256=$hash")
                }
                2 -> {
                    val hash = bytes.copyOfRange(1, bytes.size).toString(Charsets.US_ASCII)
                    check(probes.remove(hash)) { "Неверное подтверждение SHA-256" }
                    println("Проверка пройдена: SHA-256=$hash")
                }
                else -> error("Неизвестный тип сообщения")
            }
        }
    } catch (e: IrohException) {
        if (connection.closeReason() == null) throw e
        println("Соединение закрыто: ${connection.closeReason()}")
    } finally {
        sender.cancelAndJoin()
        lines.cancel()
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
