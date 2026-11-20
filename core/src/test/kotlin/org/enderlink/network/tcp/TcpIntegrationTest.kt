package org.enderlink.network.tcp

import org.enderlink.network.connection.Endpoint
import org.enderlink.network.protocol.FrameCodec
import org.junit.jupiter.api.Timeout
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Реальные TCP-сокеты через loopback; не требуют второго компьютера и фиксированного порта. */
@Timeout(10)
class TcpIntegrationTest {
    private fun withPair(codec: FrameCodec = FrameCodec(), test: (TcpConnection, TcpConnection) -> Unit) {
        TcpListener.open(0, "127.0.0.1", codec).use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port), codec = codec).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 2_000
                    server.readTimeoutMs = 2_000
                    test(client, server)
                }
            }
        }
    }

    @Test
    fun `сообщения передаются в обе стороны и сохраняют границы`() = withPair { client, server ->
        val messages = listOf("привет".toByteArray(), ByteArray(0), ByteArray(16_384) { it.toByte() })
        for (message in messages) client.send(message)
        for (message in messages) assertContentEquals(message, server.receive())
        val reply = "ответ".toByteArray()
        server.send(reply)
        assertContentEquals(reply, client.receive())
    }

    @Test
    fun `слишком большое сообщение не закрывает соединение`() = withPair(FrameCodec(8)) { client, server ->
        assertFailsWith<IllegalArgumentException> { client.send(ByteArray(9)) }
        val message = ByteArray(8) { it.toByte() }
        client.send(message)
        assertContentEquals(message, server.receive())
        server.send(byteArrayOf(42))
        assertContentEquals(byteArrayOf(42), client.receive())
    }

    @Test
    fun `отключение участника возвращает конец потока`() = withPair { client, server ->
        client.send(byteArrayOf(1))
        client.close()
        assertContentEquals(byteArrayOf(1), server.receive())
        assertNull(server.receive())
        client.close() // Повторное закрытие безопасно.
    }

    private fun withRawPeer(test: (Socket, TcpConnection) -> Unit) {
        TcpListener.open(0, "127.0.0.1").use { listener ->
            Socket().use { raw ->
                raw.connect(java.net.InetSocketAddress("127.0.0.1", listener.port), 2_000)
                listener.accept(2_000).use { receiver ->
                    receiver.readTimeoutMs = 200
                    test(raw, receiver)
                }
            }
        }
    }

    @Test
    fun `таймаут внутри заголовка и содержимого закрывает соединение`() {
        for (partial in listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 3, 42))) {
            withRawPeer { raw, receiver ->
                raw.getOutputStream().write(partial)
                assertFailsWith<SocketTimeoutException> { receiver.receive() }
                assertFailsWith<SocketException> { receiver.send(byteArrayOf(1)) }
            }
        }
    }

    @Test
    fun `обрыв внутри сообщения закрывает соединение`() = withRawPeer { raw, receiver ->
        raw.getOutputStream().write(byteArrayOf(0, 0, 0, 3, 42))
        raw.shutdownOutput()
        assertFailsWith<EOFException> { receiver.receive() }
        assertFailsWith<SocketException> { receiver.send(byteArrayOf(1)) }
    }

    @Test
    fun `недопустимый заголовок закрывает соединение`() = withRawPeer { raw, receiver ->
        raw.getOutputStream().write(byteArrayOf(-1, -1, -1, -1))
        assertFailsWith<IOException> { receiver.receive() }
        assertFailsWith<SocketException> { receiver.send(byteArrayOf(1)) }
    }

    @Test
    fun `одновременные отправители не перемешивают кадры`() = withPair { client, server ->
        val executor = Executors.newFixedThreadPool(3)
        try {
            val received = executor.submit<List<Int>> {
                List(80) {
                    val message = requireNotNull(server.receive())
                    assertEquals(1_024, message.size)
                    assertTrue(message.all { it == message[0] })
                    message[0].toInt()
                }
            }
            val start = CountDownLatch(1)
            val writers = (0..1).map { writer ->
                executor.submit {
                    start.await()
                    repeat(40) { client.send(ByteArray(1_024) { writer.toByte() }) }
                }
            }
            start.countDown()
            writers.forEach { it.get(5, TimeUnit.SECONDS) }
            val result = received.get(5, TimeUnit.SECONDS)
            assertEquals(40, result.count { it == 0 })
            assertEquals(40, result.count { it == 1 })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `таймаут accept не мешает последующему подключению`() {
        TcpListener.open(0, "127.0.0.1").use { listener ->
            assertFailsWith<SocketTimeoutException> { listener.accept(100) }
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    server.readTimeoutMs = 2_000
                    client.send(byteArrayOf(7))
                    assertContentEquals(byteArrayOf(7), server.receive())
                }
            }
        }
    }

    @Test
    fun `закрытие listener прерывает accept и освобождает порт`() {
        val listener = TcpListener.open(0, "127.0.0.1")
        val port = listener.port
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val waiting = executor.submit {
                started.countDown()
                assertFailsWith<SocketException> { listener.accept() }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            listener.close()
            waiting.get(2, TimeUnit.SECONDS)
            TcpListener.open(port, "127.0.0.1").use { assertEquals(port, it.port) }
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `закрытие connection прерывает receive`() = withPair { client, server ->
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val waiting = executor.submit {
                started.countDown()
                assertFailsWith<SocketException> { server.receive() }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            server.close()
            waiting.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
