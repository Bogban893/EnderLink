package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.transfer.TransferReceiver
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
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
import kotlin.test.assertTrue

@Timeout(25)
class ResumableDirectoryTransferTest {
    @TempDir lateinit var root: Path
    private val block = ResumeProtocol.BLOCK_SIZE

    private fun source(): Path {
        val source = Files.createDirectory(root.resolve("Мой мир"))
        Files.createDirectories(source.resolve("empty/nested"))
        Files.write(source.resolve("a-small"), byteArrayOf(1, 2, 3))
        Files.write(source.resolve("b-large"), ByteArray(block * 2 + 17) { (it * 17).toByte() })
        Files.createFile(source.resolve("zero"))
        return source
    }

    private fun pair(test: (TcpConnection, TcpConnection) -> Unit) {
        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 4_000
                    server.readTimeoutMs = 4_000
                    test(client, server)
                }
            }
        }
    }

    private fun attempt(source: Path, output: Path, dropAt: Int? = null, reject: Boolean = false): Int {
        val sender = ResumableDirectorySender(source)
        var dataMessages = 0
        pair { client, server ->
            val counted = object : Connection by client {
                override fun send(payload: ByteArray) {
                    if (payload.size > 4 && payload[4].toInt() == Protocol.DATA) {
                        if (dataMessages + 1 == dropAt) {
                            client.close()
                            throw IOException("Имитируем обрыв")
                        }
                        dataMessages++
                    }
                    client.send(payload)
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val receiving = executor.submit {
                    server.use {
                        if (dropAt != null || reject) {
                            assertFailsWith<IOException> { TransferReceiver().receive(it, output) }
                        } else TransferReceiver().receive(it, output)
                    }
                }
                if (dropAt != null || reject) assertFailsWith<IOException> { sender.send(counted) }
                else sender.send(counted)
                receiving.get(10, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
        return dataMessages
    }

    private fun state(output: Path): Path = Files.list(output).use { paths ->
        paths.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(".enderlink-directory-") }.findFirst().orElseThrow()
    }

    private fun assertTree(source: Path, target: Path) {
        val expected = Files.walk(source).use { paths -> paths.map { source.relativize(it).toString() }.toList().toSet() }
        val actual = Files.walk(target).use { paths -> paths.map { target.relativize(it).toString() }.toList().toSet() }
        assertEquals(expected, actual)
        Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            assertContentEquals(Files.readAllBytes(it), Files.readAllBytes(target.resolve(source.relativize(it))))
        } }
    }

    @Test
    fun `повторный запуск пропускает готовый файл и проверенные блоки следующего`() {
        val source = source()
        val output = root.resolve("out")
        assertEquals(2, attempt(source, output, dropAt = 3))
        assertFalse(Files.exists(output.resolve(source.fileName)))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(state(output).resolve("tree/a-small")))
        assertEquals(2, attempt(source, output))
        assertTree(source, output.resolve(source.fileName))
        Files.list(output).use { paths -> assertEquals(1L, paths.filter { Files.isDirectory(it) }.count()) }
    }

    @Test
    fun `поврежденный готовый файл передается заново`() {
        val source = source()
        val output = root.resolve("out")
        attempt(source, output, dropAt = 3)
        Files.write(state(output).resolve("tree/a-small"), byteArrayOf(9, 9, 9))
        assertEquals(3, attempt(source, output))
        assertTree(source, output.resolve(source.fileName))
    }

    @Test
    fun `измененный состав и содержимое создают отдельное состояние`() {
        val source = source()
        val output = root.resolve("out")
        attempt(source, output, dropAt = 3)
        val previous = state(output)
        Files.delete(source.resolve("a-small"))
        Files.write(source.resolve("b-large"), ByteArray(block * 2 + 17) { 42 })
        Files.writeString(source.resolve("new"), "new data")
        assertEquals(4, attempt(source, output))
        assertTrue(Files.exists(previous))
        assertTree(source, output.resolve(source.fileName))
        assertFalse(Files.exists(output.resolve(source.fileName).resolve("a-small")))
    }

    @Test
    fun `пустое дерево и существующий результат`() {
        val source = Files.createDirectory(root.resolve("empty"))
        val output = root.resolve("out")
        assertEquals(0, attempt(source, output))
        assertTree(source, output.resolve("empty"))
        attempt(source, output, reject = true)
        assertTree(source, output.resolve("empty"))
    }

    @Test
    fun `символические ссылки в источнике отклоняются`() {
        val source = Files.createDirectory(root.resolve("world"))
        Files.createSymbolicLink(source.resolve("link"), Files.writeString(root.resolve("private"), "secret"))
        assertFailsWith<IOException> { ResumableDirectorySender(source) }
    }

    @Test
    fun `поддельный манифест и выход из каталога отклоняются`() {
        for (unsafe in listOf("../escape", "/absolute", "a/../escape", "a\\escape")) {
            val entry = DirectoryManifest.Entry(unsafe, true, 0, ByteArray(0))
            pair { client, server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val receiving = executor.submit {
                        server.use { assertFailsWith<IOException> { TransferReceiver().receive(it, root.resolve("out")) } }
                    }
                    Protocol.send(client, DirectoryManifest.HEADER,
                        DirectoryManifest.encodeHeader("world", 0, 1, DirectoryManifest.hash(listOf(entry))))
                    Protocol.expect(client, Protocol.READY)
                    Protocol.send(client, DirectoryManifest.ENTRY, entry.encode())
                    receiving.get(5, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
        }
        assertFalse(Files.exists(root.resolve("escape")))
        assertFalse(Files.exists(root.resolve("out/world")))
    }

    @Test
    fun `лишний файл в сохраненном дереве не попадает в результат`() {
        val source = source()
        val output = root.resolve("out")
        attempt(source, output, dropAt = 3)
        Files.writeString(state(output).resolve("tree/unexpected"), "extra")
        attempt(source, output, reject = true)
        assertFalse(Files.exists(output.resolve(source.fileName)))
    }
}
