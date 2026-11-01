package org.enderlink.network.tcp

import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.protocol.FrameCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Соединение через TCP. Допускает одного читателя и несколько отправителей. */
class TcpConnection private constructor(
    private val socket: Socket,
    private val codec: FrameCodec,
) : Connection {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val sendLock = Any()

    /** 0 — без таймаута. Любая ошибка чтения, включая таймаут, закрывает соединение. */
    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            require(value >= 0) { "Таймаут не может быть отрицательным" }
            socket.soTimeout = value
        }

    override fun send(payload: ByteArray) {
        synchronized(sendLock) {
            try {
                codec.write(output, payload)
            } catch (e: IOException) {
                closeAfterFailure(e)
                throw e
            }
        }
    }

    override fun receive(): ByteArray? {
        return try {
            codec.read(input)
        } catch (e: IOException) {
            // После частичного чтения восстановить границу кадра уже нельзя.
            closeAfterFailure(e)
            throw e
        }
    }

    override fun close() {
        socket.close()
    }

    private fun closeAfterFailure(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
    }

    companion object {
        fun connect(
            endpoint: Endpoint,
            connectTimeoutMs: Int = 5_000,
            codec: FrameCodec = FrameCodec(),
        ): TcpConnection {
            require(connectTimeoutMs > 0) { "Таймаут подключения должен быть положительным" }
            val socket = Socket()
            return wrap(socket, codec) {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
            }
        }

        fun fromSocket(socket: Socket, codec: FrameCodec = FrameCodec()): TcpConnection =
            wrap(socket, codec) { }

        private inline fun wrap(socket: Socket, codec: FrameCodec, setup: () -> Unit): TcpConnection =
            try {
                setup()
                socket.tcpNoDelay = true // для чата задержка важнее объединения мелких пакетов
                TcpConnection(socket, codec)
            } catch (e: Throwable) {
                try {
                    socket.close()
                } catch (closeFailure: Exception) {
                    e.addSuppressed(closeFailure)
                }
                throw e
            }
    }
}
