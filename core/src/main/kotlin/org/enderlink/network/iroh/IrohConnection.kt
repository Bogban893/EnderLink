package org.enderlink.network.iroh

import computer.iroh.BiStream
import computer.iroh.Connection as NativeConnection
import computer.iroh.IrohException
import computer.iroh.PathSnapshot
import computer.iroh.WatchHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.enderlink.network.connection.Connection
import org.enderlink.network.protocol.FrameCodec
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Надёжные кадры поверх QUIC; один читатель, параллельные send сериализуются. */
class IrohConnection internal constructor(
    private val native: NativeConnection,
    private val stream: BiStream,
    private val watcher: WatchHandle,
) : Connection {
    private val send = stream.send()
    private val recv = stream.recv()
    private val lifetime = Job()
    private val closed = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile var readTimeoutMs: Int = 0
        set(value) { require(value >= 0); field = value }

    val remoteId: String get() = native.remoteId().use { it.toString() }
    fun status(): String = describePaths(native.paths())

    override fun send(payload: ByteArray) {
        require(payload.size <= FrameCodec.DEFAULT_MAX_PAYLOAD) { "Сообщение превышает допустимый размер" }
        synchronized(sendLock) {
            io {
                withTimeout(30_000) {
                    send.writeAll(ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array())
                }
            }
        }
    }

    override fun receive(): ByteArray? = io {
        suspend fun read(): ByteArray? {
            val first = recv.read(1u)
            if (first.isEmpty()) return null
            val size = ByteBuffer.wrap(first + recv.readExact(3u)).int
            if (size !in 0..FrameCodec.DEFAULT_MAX_PAYLOAD) throw IOException("Недопустимый размер сообщения: $size")
            return if (size == 0) byteArrayOf() else recv.readExact(size.toUInt())
        }
        val timeout = readTimeoutMs
        if (timeout == 0) read() else withTimeout(timeout.toLong()) { read() }
    }

    private fun <T> io(block: suspend () -> T): T {
        if (closed.get()) throw IOException("Соединение закрыто")
        return try { runBlocking(lifetime) { block() } }
        catch (e: Exception) {
            abort()
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw SocketTimeoutException("Таймаут обмена данными iroh").apply { initCause(e) }
            }
            if (e is IOException) throw e
            val detail = if (e is IrohException) e.message() else e.message
            throw IOException("Соединение iroh прервано: $detail", e)
        }
    }

    /** Аварийное завершение без ожидания отправки; проверенные блоки остаются для докачки. */
    fun abort() = shutdown(false)

    override fun close() = shutdown(true)

    private fun shutdown(graceful: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel()
        synchronized(sendLock) {
            try {
                // Дожидаемся подтверждения QUIC для последнего кадра (например SUCCESS файла).
                if (graceful) runCatching {
                    runBlocking { withTimeout(3_000) { send.finish(); send.stopped() } }
                }
            } finally {
                try { runCatching { native.close(if (graceful) 0L else 1L, "closed".toByteArray()) } }
                finally {
                    runCatching { runBlocking { withTimeout(3_000) { watcher.stop() } } }; watcher.close()
                    try { send.close() } finally {
                        try { recv.close() } finally { stream.close(); native.close() }
                    }
                }
            }
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
