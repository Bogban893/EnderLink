package org.enderlink.irohtest

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohException
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import computer.iroh.presetN0
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
Добавьте ID друг друга на обоих компьютерах. Один запускает listen, другой connect.
В чате: /probe — проверка данных, /status — текущий путь, /quit — выход.
Используются публичные поиск и relay iroh; собственный сервер не требуется.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) { println(HELP); return }
    try {
        val (directory, command) = parseArguments(args.toList())
        Profile.open(directory).use { profile ->
            when (command[0]) {
                "id" -> println("Ваш ID: ${profile.id}")
                "friends" -> {
                    val friends = profile.friends()
                    if (friends.isEmpty()) println("Друзей пока нет. Используйте add <имя> <ID>.")
                    else friends.forEach { (name, id) -> println("$name $id") }
                }
                "add" -> { profile.add(command[1], command[2]); println("Друг ${command[1]} сохранён.") }
                "remove" -> { profile.remove(command[1]); println("Друг ${command[1]} удалён.") }
                else -> runBlocking { startChat(profile, command) }
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

private fun parseArguments(args: List<String>): Pair<Path, List<String>> {
    var directory = Path.of(System.getProperty("user.home"), ".enderlink", "iroh-test")
    val command = mutableListOf<String>()
    var profileSpecified = false
    var index = 0
    while (index < args.size) {
        if (args[index] == "--profile") {
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
    return directory to command
}

private suspend fun startChat(profile: Profile, command: List<String>) {
    val target = if (command[0] == "connect") profile.friendId(command[1]) else null
    require(target != null || profile.friends().isNotEmpty()) { "Сначала добавьте друга командой add <имя> <ID>" }
    println("Ваш ID: ${profile.id}")
    println("Подключение к публичной сети iroh…")
    val endpoint = Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = profile.key, alpns = listOf(ALPN)))
    try {
        withTimeout(60_000) { endpoint.online() }
        println("Узел онлайн. ID сохраняется после перезапуска и смены сети.")
        val connection = if (target == null) {
            println("Ожидание сохранённого друга…")
            acceptFriend(endpoint, profile, ::println)
        } else {
            println("Поиск ${command[1]} по постоянному ID…")
            EndpointId.fromString(target).use { id ->
                EndpointAddr(id, null, emptyList()).use { addr ->
                    withTimeout(60_000) { endpoint.connect(addr, ALPN) }
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
