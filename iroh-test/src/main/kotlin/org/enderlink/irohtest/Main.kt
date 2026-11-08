package org.enderlink.irohtest

import org.enderlink.network.iroh.IrohConnection
import org.enderlink.network.iroh.IrohNode
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.network.iroh.validateRelay
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val HELP = """Диагностический iroh-стенд (общая реализация из core)
  id | friends | add <имя> <ID> | remove <имя>
  listen [--debug]
  connect <имя> [--relay <https://адрес>] [--debug]
Для всех команд: --profile <каталог>. По умолчанию ~/.enderlink/iroh-test.
В чате: /probe, /status, /quit. Передача файлов: используйте основной cli.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) { println(HELP); return }
    try {
        val command = mutableListOf<String>()
        var path = IrohProfile.defaultDirectory()
        var relay: String? = null
        var debug = false
        val seen = mutableSetOf<String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index++]
            if (arg == "--debug") { require(seen.add(arg)); debug = true }
            else if (arg in setOf("--profile", "--relay")) {
                require(seen.add(arg) && index < args.size) { "Укажите значение $arg один раз" }
                val value = args[index++]
                if (arg == "--profile") { require(value.isNotBlank()); path = Path.of(value) }
                else { validateRelay(value); relay = value }
            } else command.add(arg)
        }
        val expected = when (command.firstOrNull()) {
            "id", "friends", "listen" -> 1
            "connect", "remove" -> 2
            "add" -> 3
            else -> throw IllegalArgumentException(HELP)
        }
        require(command.size == expected) { HELP }
        require(relay == null || command[0] == "connect") { "--relay используется с connect" }
        IrohProfile.open(path).use { profile ->
            when (command[0]) {
                "id" -> println("Ваш ID: ${profile.id}")
                "friends" -> profile.friends().forEach { (name, id) -> println("$name $id") }
                "add" -> { profile.add(command[1], command[2]); println("Друг сохранён.") }
                "remove" -> { profile.remove(command[1]); println("Друг удалён.") }
                else -> IrohNode.open(profile, debug).use { node ->
                    val connection = if (command[0] == "listen") node.accept(IrohPurpose.DIAGNOSTIC)
                    else node.connect(command[1], IrohPurpose.DIAGNOSTIC, relay)
                    connection.use { println("Чат открыт. Текст, /probe, /status, /quit."); chat(it) }
                }
            }
        }
    } catch (e: Exception) { System.err.println("Ошибка: ${e.message}"); exitProcess(1) }
    catch (e: LinkageError) { System.err.println("Не удалось загрузить iroh: ${e.message}"); exitProcess(1) }
}

private fun chat(connection: IrohConnection) {
    val probes = ConcurrentHashMap.newKeySet<String>()
    thread(name = "iroh-console", isDaemon = true) {
        try {
            while (true) {
                val line = readlnOrNull() ?: break
                if (line == "/quit") break
                when (line) {
                    "/status" -> println(connection.status())
                    "/probe" -> {
                        val bytes = ByteArray(32 * 1024).also(SecureRandom()::nextBytes)
                        probes.add(sha256(bytes)); connection.send(byteArrayOf(1) + bytes)
                        println("Проверка отправлена: ${bytes.size} байт")
                    }
                    else -> connection.send(byteArrayOf(0) + line.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) { System.err.println("Ошибка отправки: ${e.message}") }
        finally { connection.close() }
    }
    try {
        while (true) {
            val bytes = connection.receive() ?: break
            require(bytes.isNotEmpty())
            when (bytes[0].toInt()) {
                0 -> println("Собеседник: ${bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)}")
                1 -> {
                    val hash = sha256(bytes.copyOfRange(1, bytes.size))
                    connection.send(byteArrayOf(2) + hash.toByteArray(Charsets.US_ASCII))
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
    } catch (e: IOException) { println("Соединение закрыто: ${e.message}") }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
