package org.enderlink.network.tcp

import org.enderlink.network.protocol.FrameCodec
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Приём входящих TCP-соединений. Вызовы accept выполняются одним потоком. */
class TcpListener private constructor(
    private val server: ServerSocket,
    private val codec: FrameCodec,
) : AutoCloseable {

    /** Реальный порт (полезно, если открывали с портом 0). */
    val port: Int get() = server.localPort

    /**
     * Ждёт подключение. [timeoutMs] = 0 означает ждать бесконечно.
     * После `close()` ожидающий вызов завершается с `SocketException`,
     * по таймауту выбрасывается `SocketTimeoutException`.
     */
    fun accept(timeoutMs: Int = 0): TcpConnection {
        require(timeoutMs >= 0) { "Таймаут не может быть отрицательным" }
        server.soTimeout = timeoutMs
        return TcpConnection.fromSocket(server.accept(), codec)
    }

    override fun close() = server.close()

    companion object {
        /** [port] = 0 выбирает свободный порт; [host] = null слушает на всех интерфейсах. */
        fun open(port: Int, host: String? = null, codec: FrameCodec = FrameCodec()): TcpListener {
            require(port in 0..65535) { "Порт должен быть в диапазоне 0..65535: $port" }
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(if (host == null) InetSocketAddress(port) else InetSocketAddress(host, port))
            } catch (e: Throwable) {
                try {
                    server.close()
                } catch (closeFailure: Exception) {
                    e.addSuppressed(closeFailure)
                }
                throw e
            }
            return TcpListener(server, codec)
        }
    }
}
