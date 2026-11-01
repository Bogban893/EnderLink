package org.enderlink.transfer

import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(15)
class FileTransferTest {
    @TempDir
    lateinit var root: Path

    private fun pair(test: (TcpConnection, TcpConnection) -> Unit) {
        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 3_000
                    server.readTimeoutMs = 3_000
                    test(client, server)
                }
            }
        }
    }

    @Test
    fun `пустой и многоблочный файл передаются с подтверждением`() {
        for (size in listOf(0, Protocol.BLOCK_SIZE * 3 + 17)) {
            val source = root.resolve("мир-$size.bin")
            val content = ByteArray(size) { (it * 31).toByte() }
            Files.write(source, content)
            val output = root.resolve("received-$size")
            pair { client, server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val receiving = executor.submit<Path> { FileReceiver().receive(server, output) }
                    val progress = mutableListOf<Long>()
                    FileSender().send(client, source) { done, total ->
                        assertEquals(size.toLong(), total)
                        progress.add(done)
                    }
                    val result = receiving.get(5, TimeUnit.SECONDS)
                    assertContentEquals(content, Files.readAllBytes(result))
                    assertEquals(size.toLong(), progress.last())
                    Files.list(output).use { assertEquals(1L, it.count()) }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    private fun rejectTransfer(name: String, body: (TcpConnection) -> Unit, after: (Path) -> Unit) {
        val output = root.resolve("received")
        pair { client, server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val receiving = executor.submit {
                    assertFailsWith<IOException> { FileReceiver().receive(server, output) }
                }
                Protocol.send(client, Protocol.HEADER, Protocol.header(name, 3))
                body(client)
                receiving.get(5, TimeUnit.SECONDS)
                after(output)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `неверный хеш не публикует файл и удаляет временный`() {
        rejectTransfer("world.bin", { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DATA, byteArrayOf(1, 2, 3))
            Protocol.send(client, Protocol.END, ByteArray(32))
        }) { output ->
            Files.list(output).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `обрыв не оставляет готовый или временный файл`() {
        rejectTransfer("world.bin", { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DATA, byteArrayOf(1))
            client.close()
        }) { output ->
            Files.list(output).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `пути за пределы каталога отклоняются`() {
        for (name in listOf("../escape", "..\\escape", "/escape", "C:escape", ".", "..")) {
            rejectTransfer(name, {}) { output -> assertFalse(Files.exists(output)) }
        }
        assertFalse(Files.exists(root.resolve("escape")))
    }

    @Test
    fun `существующий файл не перезаписывается`() {
        val output = Files.createDirectory(root.resolve("received"))
        val original = byteArrayOf(8, 9)
        Files.write(output.resolve("world.bin"), original)
        rejectTransfer("world.bin", {}) {
            assertContentEquals(original, Files.readAllBytes(it.resolve("world.bin")))
            Files.list(it).use { files -> assertEquals(1L, files.count()) }
        }
    }

    @Test
    fun `лишние байты сверх объявленного размера отклоняются`() {
        rejectTransfer("world.bin", { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DATA, ByteArray(4))
        }) { output ->
            Files.list(output).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `без подтверждения получателя отправитель не сообщает успех`() {
        val source = Files.write(root.resolve("source.bin"), byteArrayOf(1))
        pair { client, server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    assertEquals(Protocol.HEADER, Protocol.read(server).first)
                    Protocol.send(server, Protocol.READY)
                    assertEquals(Protocol.DATA, Protocol.read(server).first)
                    assertEquals(Protocol.END, Protocol.read(server).first)
                    server.close()
                }
                assertFailsWith<EOFException> { FileSender().send(client, source) }
                peer.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
