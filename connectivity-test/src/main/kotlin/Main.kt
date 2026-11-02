package org.enderlink

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val help = """Проверка прямого WebRTC-соединения (без TURN/relay).
  offer <файл-приглашение> [--stun stun:адрес:порт | --no-stun]
  answer <файл-приглашение> <файл-ответ> [--stun stun:адрес:порт | --no-stun]

STUN по умолчанию: stun:stun.l.google.com:19302
Оба процесса должны оставаться запущенными во время обмена файлами.
После подключения: текст — сообщение, /probe — проверка 16 КиБ, /quit — выход.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) {
        println(help)
        return
    }
    try {
        val mode = args[0]
        require(mode in setOf("offer", "answer")) { "Ожидается offer или answer" }
        val count = if (mode == "offer") 2 else 3
        require(args.size >= count) { help }
        val stun = when (args.drop(count)) {
            emptyList<String>() -> "stun:stun.l.google.com:19302"
            listOf("--no-stun") -> null
            else -> {
                require(args.size == count + 2 && args[count] == "--stun") { help }
                args[count + 1].also {
                    require(it.startsWith("stun:") && it.length > 5) { "Нужен адрес stun:хост:порт" }
                }
            }
        }
        val offer = if (mode == "answer") Signal.read(Path.of(args[1]), "offer") else null
        val output = Path.of(args[count - 1])
        require(!Files.exists(output)) { "Файл уже существует: $output" }
        println("STUN: ${stun ?: "отключён"}. TURN/relay: отключён.")
        WebRtcPeer(stun, ::println).use { peer ->
            val description = if (offer == null) peer.offer() else peer.answer(offer)
            Files.writeString(output, Signal.encode(mode, description), CREATE_NEW)
            println("Файл ${output.toAbsolutePath()} готов. Отправьте его собеседнику.")
            if (mode == "offer") {
                println("Оставьте программу запущенной. Получите файл-ответ и введите путь к нему:")
                val path = readlnOrNull() ?: return
                peer.acceptAnswer(Signal.read(Path.of(path), "answer"))
            }
            println("Ожидание канала данных (до 60 секунд)…")
            peer.awaitOpen()
            println("Канал открыт. Введите сообщение, /probe или /quit.")
            chat(peer)
        }
    } catch (e: Exception) {
        System.err.println("Ошибка: ${e.message ?: e.javaClass.simpleName}")
        exitProcess(1)
    } catch (e: LinkageError) {
        System.err.println("Не удалось загрузить WebRTC: ${e.message}. См. connectivity-test/README.md")
        exitProcess(1)
    }
}

private fun chat(peer: WebRtcPeer) {
    val probes = ConcurrentHashMap.newKeySet<String>()
    thread(name = "connectivity-console", isDaemon = true) {
        try {
            while (true) {
                val line = readlnOrNull() ?: break
                if (line == "/quit") break
                if (line == "/probe") {
                    val bytes = ByteArray(16 * 1024 - 1).also(SecureRandom()::nextBytes)
                    val hash = sha256(bytes)
                    probes.add(hash)
                    peer.send(byteArrayOf(1) + bytes)
                    println("Проверка отправлена: SHA-256=$hash")
                } else {
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    if (bytes.size > 16 * 1024 - 1) {
                        println("Сообщение слишком длинное (максимум 16383 байта UTF-8).")
                        continue
                    }
                    peer.send(byteArrayOf(0) + bytes)
                }
            }
        } catch (e: Exception) {
            peer.stop(IOException("Ошибка отправки: ${e.message}", e))
        } finally {
            peer.stop()
        }
    }
    while (true) {
        val bytes = peer.receive() ?: break
        require(bytes.isNotEmpty()) { "Пустое сообщение протокола" }
        when (bytes[0].toInt()) {
            0 -> println("Собеседник: ${bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)}")
            1 -> {
                val hash = sha256(bytes.copyOfRange(1, bytes.size))
                println("Получена бинарная проверка: ${bytes.size - 1} байт, SHA-256=$hash")
                peer.send(byteArrayOf(2) + hash.toByteArray(Charsets.US_ASCII))
            }
            2 -> {
                val hash = bytes.copyOfRange(1, bytes.size).toString(Charsets.US_ASCII)
                check(probes.remove(hash)) { "Неожиданное или неверное подтверждение SHA-256" }
                println("Проверка пройдена: собеседник подтвердил SHA-256=$hash")
            }
            else -> error("Неизвестное сообщение протокола")
        }
    }
    println("Соединение закрыто.")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
