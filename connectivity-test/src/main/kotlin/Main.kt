package org.enderlink

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val help = """Проверка прямого WebRTC-соединения (без TURN/relay).
  offer [--stun stun:адрес:порт | --no-stun]
  answer [--stun stun:адрес:порт | --no-stun]

STUN по умолчанию: stun:stun.l.google.com:19302
Скопируйте код приглашения собеседнику, затем вставьте полученный код ответа.
Оба процесса должны оставаться запущенными во время обмена кодами.
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
        val stun = when (args.drop(1)) {
            emptyList<String>() -> "stun:stun.l.google.com:19302"
            listOf("--no-stun") -> null
            else -> {
                require(args.size == 3 && args[1] == "--stun") { help }
                args[2].also {
                    require(it.startsWith("stun:") && it.length > 5) { "Нужен адрес stun:хост:порт" }
                }
            }
        }
        val offer = if (mode == "answer") readSignalCode("offer") ?: return else null
        println("STUN: ${stun ?: "отключён"}. TURN/relay: отключён.")
        WebRtcPeer(stun, ::println).use { peer ->
            val description = if (offer == null) peer.offer() else peer.answer(offer)
            println("Код ${if (mode == "offer") "приглашения" else "ответа"} — скопируйте всю следующую строку собеседнику:")
            println(Signal.encode(mode, description))
            if (mode == "offer") {
                println("Оставьте программу запущенной и получите код ответа.")
                val answer = readSignalCode("answer") ?: return
                peer.acceptAnswer(answer)
            }
            println("Ожидание канала данных (до ${WebRtcPeer.CONNECT_TIMEOUT_SECONDS / 60} минут)…")
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

/** Неверный код можно вставить повторно, сохранив живую сессию. */
private fun readSignalCode(expected: String): String? {
    while (true) {
        println("Вставьте код ${if (expected == "offer") "приглашения" else "ответа"} одной строкой (или /quit для выхода):")
        val code = readlnOrNull() ?: return null
        if (code.trim() == "/quit") return null
        try {
            return Signal.decode(code, expected)
        } catch (e: IllegalArgumentException) {
            System.err.println("Некорректный код: ${e.message}. Попробуйте ещё раз.")
        }
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
