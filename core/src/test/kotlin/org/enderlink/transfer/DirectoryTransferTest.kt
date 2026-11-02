package org.enderlink.transfer

import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
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

@Timeout(15)
class DirectoryTransferTest {
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
    fun `дерево с пустыми папками и файлами передается целиком`() {
        val source = Files.createDirectory(root.resolve("Мой мир"))
        Files.createDirectories(source.resolve("region/empty"))
        Files.createDirectory(source.resolve("пустая папка"))
        Files.write(source.resolve("level.dat"), byteArrayOf(7, 8, 9))
        val content = ByteArray(Protocol.BLOCK_SIZE * 2 + 7) { (it * 17).toByte() }
        Files.write(source.resolve("region/r.0.0.mca"), content)
        Files.createFile(source.resolve("region/zero"))
        val output = root.resolve("received")
        pair { client, server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val receiverProgress = mutableListOf<Long>()
                val receiving = executor.submit<Path> {
                    TransferReceiver().receive(server, output) { done, _ -> receiverProgress.add(done) }
                }
                val sent = mutableListOf<Long>()
                DirectorySender().send(client, source.resolve(".")) { done, total ->
                    assertEquals(content.size.toLong() + 3, total)
                    sent.add(done)
                }
                val target = receiving.get(5, TimeUnit.SECONDS)
                assertEquals(output.toRealPath().resolve("Мой мир"), target)
                val expected = Files.walk(source).use { paths -> paths.map { source.relativize(it).toString() }.toList().toSet() }
                val actual = Files.walk(target).use { paths -> paths.map { target.relativize(it).toString() }.toList().toSet() }
                assertEquals(expected, actual)
                assertContentEquals(content, Files.readAllBytes(target.resolve("region/r.0.0.mca")))
                assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(target.resolve("level.dat")))
                assertEquals(content.size.toLong() + 3, sent.last())
                assertEquals(sent.last(), receiverProgress.last())
                assertTrue(sent.zipWithNext().all { (a, b) -> a <= b })
                Files.list(output).use { assertEquals(1L, it.count()) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `пустой корневой каталог сохраняется`() {
        val source = Files.createDirectory(root.resolve("empty"))
        pair { client, server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val receiving = executor.submit<Path> { TransferReceiver().receive(server, root.resolve("out")) }
                DirectorySender().send(client, source)
                val target = receiving.get(5, TimeUnit.SECONDS)
                assertTrue(Files.isDirectory(target))
                Files.list(target).use { assertEquals(0L, it.count()) }
            } finally { executor.shutdownNow() }
        }
    }

    private fun rejected(
        total: Long = 0, count: Int = 1,
        setup: (Path) -> Unit = {},
        script: (TcpConnection) -> Unit,
    ) {
        val output = root.resolve("out")
        Files.createDirectories(output)
        setup(output)
        pair { client, server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val receiving = executor.submit {
                    server.use {
                        assertFailsWith<IOException> { TransferReceiver().receive(it, output) }
                    }
                }
                Protocol.send(client, Protocol.DIRECTORY_HEADER, Protocol.directoryHeader("world", total, count))
                script(client)
                receiving.get(5, TimeUnit.SECONDS)
                Files.list(output).use { paths ->
                    assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".enderlink-") })
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `существующий каталог не меняется`() {
        rejected(setup = {
            Files.createDirectory(it.resolve("world"))
            Files.writeString(it.resolve("world/original"), "keep")
        }, script = { client ->
            assertFailsWith<IOException> { Protocol.expect(client, Protocol.READY) }
        })
        assertEquals("keep", Files.readString(root.resolve("out/world/original")))
    }

    @Test
    fun `небезопасные относительные пути отклоняются`() {
        for (path in listOf("../escape", "/absolute", "a/../escape", "a//b", "C:/escape", "a\\b")) {
            rejected(script = { client ->
                Protocol.expect(client, Protocol.READY)
                Protocol.send(client, Protocol.DIRECTORY_ENTRY, Protocol.header(path, 0))
            })
        }
        assertFalse(Files.exists(root.resolve("out/world")))
        assertFalse(Files.exists(root.resolve("out/escape")))
    }

    @Test
    fun `повторные пути и незавершенный список не публикуются`() {
        rejected(count = 2, script = { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DIRECTORY_ENTRY, Protocol.header("empty", 0))
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DIRECTORY_ENTRY, Protocol.header("empty", 0))
        })
        rejected(count = 2, script = { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DIRECTORY_END)
        })
        assertFalse(Files.exists(root.resolve("out/world")))
    }

    @Test
    fun `обрыв после одного файла удаляет все временное дерево`() {
        val file = Files.write(root.resolve("level.dat"), byteArrayOf(1, 2, 3))
        rejected(total = 3, count = 2, script = { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.FILE_ENTRY, Protocol.header("level.dat", 3))
            Protocol.expect(client, Protocol.READY)
            FileSender().send(client, file)
            client.close()
        })
        assertFalse(Files.exists(root.resolve("out/world")))
    }

    @Test
    fun `поврежденный файл не публикует каталог`() {
        rejected(total = 1, script = { client ->
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.FILE_ENTRY, Protocol.header("level.dat", 1))
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.HEADER, Protocol.header("level.dat", 1))
            Protocol.expect(client, Protocol.READY)
            Protocol.send(client, Protocol.DATA, byteArrayOf(1))
            Protocol.send(client, Protocol.END, ByteArray(32))
        })
        assertFalse(Files.exists(root.resolve("out/world")))
    }

    @Test
    fun `символическая ссылка в источнике отклоняется до отправки`() {
        val source = Files.createDirectory(root.resolve("world"))
        Files.createSymbolicLink(source.resolve("link"), Files.writeString(root.resolve("outside"), "private"))
        pair { client, _ ->
            assertFailsWith<IOException> { DirectorySender().send(client, source) }
        }
    }
}
