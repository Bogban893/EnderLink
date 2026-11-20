package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.transfer.TransferReceiver
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
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

@Timeout(20)
class ResumableFileTransferTest {
    @TempDir lateinit var root: Path
    private val blockSize = ResumeProtocol.BLOCK_SIZE

    /** Новый sender/receiver и новые TCP-сокеты на каждую попытку. */
    private fun attempt(source: Path, output: Path, dropAt: Int? = null, rejectExisting: Boolean = false, dropSuccess: Boolean = false): Int {
        val sender = ResumableFileSender(source)
        var sent = 0
        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 3_000
                    server.readTimeoutMs = 3_000
                    val transport = object : Connection by client {
                        override fun send(payload: ByteArray) {
                            if (payload.size > 4 && payload[4].toInt() == 2) {
                                if (sent + 1 == dropAt) {
                                    client.close()
                                    throw IOException("Имитируем обрыв")
                                }
                                sent++
                            }
                            client.send(payload)
                        }
                    }
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val receiving = executor.submit {
                            server.use {
                                val receivingConnection = object : Connection by it {
                                    override fun send(payload: ByteArray) {
                                        if (dropSuccess && payload.size > 4 && payload[4].toInt() == 5) {
                                            it.close()
                                            throw IOException("Имитируем потерю подтверждения")
                                        }
                                        it.send(payload)
                                    }
                                }
                                if (dropAt != null || rejectExisting || dropSuccess) {
                                    assertFailsWith<IOException> { TransferReceiver().receive(receivingConnection, output) }
                                } else {
                                    TransferReceiver().receive(receivingConnection, output)
                                }
                            }
                        }
                        if (dropAt != null || rejectExisting || dropSuccess) {
                            assertFailsWith<IOException> { sender.send(transport) }
                        } else sender.send(transport)
                        receiving.get(8, TimeUnit.SECONDS)
                    } finally { executor.shutdownNow() }
                }
            }
        }
        return sent
    }

    private fun cache(output: Path): Path = Files.list(output).use { paths ->
        paths.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(".enderlink-resume-") }.findFirst().orElseThrow()
    }

    @Test
    fun `после обрыва повторно передаются только недостающие блоки`() {
        val content = ByteArray(blockSize * 3 + 19) { (it * 31).toByte() }
        val source = Files.write(root.resolve("мир.bin"), content)
        val output = root.resolve("out")
        assertEquals(1, attempt(source, output, dropAt = 2))
        assertFalse(Files.exists(output.resolve("мир.bin")))
        assertEquals(blockSize.toLong(), Files.size(cache(output).resolve("0.part")))
        assertEquals(3, attempt(source, output))
        assertContentEquals(content, Files.readAllBytes(output.resolve("мир.bin")))
        Files.list(output).use { paths -> assertEquals(0L, paths.filter { Files.isDirectory(it) }.count()) }
    }

    @Test
    fun `поврежденный сохраненный блок запрашивается повторно`() {
        val content = ByteArray(blockSize * 2 + 5) { it.toByte() }
        val source = Files.write(root.resolve("source"), content)
        val output = root.resolve("out")
        attempt(source, output, dropAt = 2)
        Files.write(cache(output).resolve("0.part"), ByteArray(blockSize))
        assertEquals(3, attempt(source, output))
        assertContentEquals(content, Files.readAllBytes(output.resolve("source")))
    }

    @Test
    fun `новое содержимое с прежними именем и размером имеет отдельное состояние`() {
        val source = Files.write(root.resolve("source"), ByteArray(blockSize * 2 + 1) { 1 })
        val output = root.resolve("out")
        attempt(source, output, dropAt = 2)
        val oldCache = cache(output)
        val changed = ByteArray(blockSize * 2 + 1) { 2 }
        Files.write(source, changed)
        assertEquals(3, attempt(source, output))
        assertContentEquals(changed, Files.readAllBytes(output.resolve("source")))
        assertEquals(blockSize.toLong(), Files.size(oldCache.resolve("0.part")))
    }

    @Test
    fun `пустой файл и файл размером с блок передаются`() {
        for (size in listOf(0, blockSize)) {
            val source = Files.write(root.resolve("source-$size"), ByteArray(size) { 7 })
            val output = root.resolve("out-$size")
            assertEquals(if (size == 0) 0 else 1, attempt(source, output))
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(output.resolve(source.fileName)))
        }
    }

    @Test
    fun `существующий результат не перезаписывается`() {
        val source = Files.write(root.resolve("source"), byteArrayOf(1))
        val output = Files.createDirectory(root.resolve("out"))
        Files.write(output.resolve("source"), byteArrayOf(9))
        attempt(source, output, rejectExisting = true)
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(output.resolve("source")))
    }

    @Test
    fun `потеря подтверждения не создаёт второй файл`() {
        val source = Files.write(root.resolve("source"), byteArrayOf(1, 2, 3))
        val output = root.resolve("out")
        attempt(source, output, dropSuccess = true)
        val target = output.resolve("source")
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(target))
        assertEquals(0, attempt(source, output))
        assertEquals(1L, Files.list(output).use { it.filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "source" }.count() })
    }

    @Test
    fun `изменение файла после вычисления хешей останавливает отправку`() {
        val source = Files.write(root.resolve("source"), byteArrayOf(1, 2, 3))
        val sender = ResumableFileSender(source)
        Files.write(source, byteArrayOf(4, 5, 6))
        val replies = java.util.ArrayDeque<ByteArray>().apply {
            add(byteArrayOf(0x45, 0x4c, 0x46, 0x31, 4))
            add(byteArrayOf(0x45, 0x4c, 0x46, 0x31, 13))
        }
        val connection = object : Connection {
            override fun send(payload: ByteArray) {}
            override fun receive(): ByteArray? = replies.poll()
            override fun close() {}
        }
        assertFailsWith<IOException> { sender.send(connection) }
    }
}
