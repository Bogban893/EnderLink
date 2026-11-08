package org.enderlink.irohtest

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.PathSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.ByteBuffer

internal val ALPN = "enderlink/iroh-chat/1".toByteArray(Charsets.US_ASCII)
private val HELLO = "ELIROH1".toByteArray(Charsets.US_ASCII)

/** Проверка постоянного ID выполняется после аутентифицированного QUIC-handshake. */
internal suspend fun acceptFriend(endpoint: Endpoint, profile: Profile, log: (String) -> Unit): Connection {
    while (true) {
        val incoming = endpoint.acceptNext() ?: throw IOException("Узел закрыт")
        val connection = incoming.use {
            try { withTimeout(20_000) { it.accept().use { accepting -> accepting.connect() } } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { log("Таймаут входящего подключения"); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("Не удалось принять соединение: ${e.message}"); null }
        } ?: continue
        val remoteId = connection.remoteId().use { it.toString() }
        val name = profile.friendName(remoteId)
        if (name != null) {
            log("Подключился друг $name ($remoteId)")
            return connection
        }
        log("Отклонён неизвестный ID: $remoteId. Добавьте друга командой add.")
        try { connection.close(1L, "unknown friend".toByteArray()) } finally { connection.close() }
    }
}

/** Один двусторонний надёжный поток, границы сообщений задаются 4-байтовой длиной. */
internal class IrohChat private constructor(private val stream: BiStream) : AutoCloseable {
    private val send = stream.send()
    private val recv = stream.recv()
    private val mutex = Mutex()

    suspend fun send(bytes: ByteArray) = mutex.withLock {
        require(bytes.size <= MAX_MESSAGE) { "Сообщение больше $MAX_MESSAGE байт" }
        send.writeAll(ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array())
    }

    suspend fun receive(): ByteArray? {
        val first = recv.read(1u)
        if (first.isEmpty()) return null
        val header = first + recv.readExact(3u)
        val size = ByteBuffer.wrap(header).int
        if (size !in 0..MAX_MESSAGE) throw IOException("Некорректная длина сообщения: $size")
        return if (size == 0) byteArrayOf() else recv.readExact(size.toUInt())
    }

    override fun close() {
        try { send.close() } finally { try { recv.close() } finally { stream.close() } }
    }

    companion object {
        const val MAX_MESSAGE = 64 * 1024

        suspend fun open(connection: Connection, initiator: Boolean): IrohChat = withTimeout(30_000) {
            val stream = if (initiator) connection.openBi() else connection.acceptBi()
            val chat = IrohChat(stream)
            try {
                if (initiator) {
                    chat.send(HELLO)
                    require(chat.receive()?.contentEquals(HELLO) == true) { "Неверное приветствие EnderLink" }
                } else {
                    require(chat.receive()?.contentEquals(HELLO) == true) { "Неверное приветствие EnderLink" }
                    chat.send(HELLO)
                }
                chat
            } catch (e: Throwable) { chat.close(); throw e }
        }
    }
}

internal fun describePaths(paths: List<PathSnapshot>): String {
    val selected = paths.filter { it.isSelected }
    if (selected.isEmpty()) return "Путь: ещё выбирается"
    return "Путь: " + selected.joinToString("; ") {
        val type = when { it.isRelay -> "relay"; it.isIp -> "напрямую"; else -> "неизвестный" }
        "$type, ${it.remoteAddr}, RTT ${it.rttMs} мс"
    }
}
