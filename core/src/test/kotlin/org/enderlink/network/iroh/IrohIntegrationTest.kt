package org.enderlink.network.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.runBlocking
import org.enderlink.network.connection.Connection
import org.enderlink.transfer.TransferReceiver
import org.enderlink.transfer.resume.ResumableDirectorySender
import org.enderlink.transfer.resume.ResumableFileSender
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Timeout(30)
class IrohIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `старый профиль сохраняет ID друзей и relay при переносе в core`() {
        val friend = IrohProfile.open(root.resolve("friend")).use { it.id }
        val own = IrohProfile.open(root.resolve("own")).use {
            it.add("Друг", friend)
            it.rememberRelay(friend, "https://example.org/")
            assertFailsWith<IllegalArgumentException> { it.rememberRelay(friend, "http://example.org/") }
            assertFailsWith<IOException> { IrohProfile.open(root.resolve("own")) }
            it.id
        }
        IrohProfile.open(root.resolve("own")).use {
            assertEquals(own, it.id)
            assertEquals(friend, it.friendId("Друг"))
            assertEquals("https://example.org/", it.relayFor(friend))
            assertFailsWith<IllegalArgumentException> { it.add("self", own) }
            it.remove("Друг")
            assertNull(it.relayFor(friend))
        }
        Files.write(root.resolve("own/secret.key"), byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { IrohProfile.open(root.resolve("own")) }
        assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(root.resolve("own/secret.key")))
    }

    private fun pair(
        serverPurpose: IrohPurpose = IrohPurpose.TRANSFER,
        clientPurpose: IrohPurpose = serverPurpose,
        unknownFirst: Boolean = false,
        action: (IrohConnection, IrohConnection, List<String>) -> Unit,
    ) {
        IrohProfile.open(root.resolve("server-profile")).use { sp ->
            IrohProfile.open(root.resolve("client-profile")).use { cp ->
                sp.add("client", cp.id); cp.add("server", sp.id)
                val server = runBlocking { Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = sp.key, alpns = listOf(IROH_ALPN))) }
                val client = runBlocking { Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = cp.key, alpns = listOf(IROH_ALPN))) }
                val logs = CopyOnWriteArrayList<String>()
                IrohNode(server, sp, logs::add).use { sn ->
                    IrohNode(client, cp, {}).use { cn ->
                        val executor = Executors.newSingleThreadExecutor()
                        try {
                            val accepting = executor.submit<IrohConnection> { sn.accept(serverPurpose) }
                            if (unknownFirst) {
                                val stranger = runBlocking { Endpoint.bind(EndpointOptions(preset = presetMinimal())) }
                                try {
                                    runBlocking {
                                        stranger.connect(server.addr(), IROH_ALPN).use { unknown -> unknown.closed() }
                                    }
                                } finally { runBlocking { stranger.shutdown() }; stranger.close() }
                            }
                            val native = runBlocking { client.connect(server.addr(), IROH_ALPN) }
                            val outgoing = runBlocking { cn.prepare(native, clientPurpose, true) }
                            outgoing.use { a ->
                                accepting.get(10, TimeUnit.SECONDS).use { b ->
                                    a.readTimeoutMs = 5_000; b.readTimeoutMs = 5_000
                                    action(a, b, logs)
                                }
                            }
                        } finally { executor.shutdownNow() }
                    }
                }
            }
        }
    }

    @Test
    fun `неизвестный ID отклоняется а сообщения больше 64 КиБ и пустые кадры проходят`() {
        pair(unknownFirst = true) { a, b, logs ->
            assertTrue(logs.any { it.startsWith("Отклонён неожидаемый ID") })
            val bytes = ByteArray(300 * 1024) { (it % 251).toByte() }
            a.send(bytes); assertContentEquals(bytes, b.receive())
            b.send("Привет 🌍".toByteArray()); assertContentEquals("Привет 🌍".toByteArray(), a.receive())
            a.send(byteArrayOf()); assertContentEquals(byteArrayOf(), b.receive())
            assertFailsWith<IllegalArgumentException> { a.send(ByteArray(1024 * 1024 + 1)) }
        }
    }

    @Test
    fun `режим чата не принимается как передача файла`() {
        assertFailsWith<IOException> { pair(IrohPurpose.TRANSFER, IrohPurpose.CHAT) { _, _, _ -> fail("Не должен открыться") } }
    }

    @Test
    fun `таймаут чтения закрывает соединение`() {
        pair { a, _, _ ->
            a.readTimeoutMs = 100
            assertFailsWith<SocketTimeoutException> { a.receive() }
            assertFailsWith<IOException> { a.receive() }
        }
    }

    private fun transfer(source: Path, output: Path, drop: Boolean = false): Int {
        val sender = ResumableFileSender(source)
        var blocks = 0
        pair { a, b, _ ->
            val executor = Executors.newSingleThreadExecutor()
            val sending = object : Connection by a {
                override fun send(payload: ByteArray) {
                    if (payload.size > 4 && payload[4].toInt() == 2) {
                        if (drop && blocks == 1) { a.abort(); throw IOException("Имитируем обрыв") }
                        blocks++
                    }
                    a.send(payload)
                }
            }
            try {
                val received = executor.submit {
                    b.use {
                        if (drop) assertFailsWith<IOException> { TransferReceiver().receive(it, output) }
                        else TransferReceiver().receive(it, output)
                    }
                }
                if (drop) assertFailsWith<IOException> { sender.send(sending) } else sender.send(sending)
                received.get(10, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
        return blocks
    }

    @Test
    fun `после обрыва файла новое iroh соединение передает только недостающие блоки`() {
        val bytes = ByteArray(256 * 1024 * 3 + 19) { (it * 31).toByte() }
        val source = Files.write(root.resolve("мир.bin"), bytes)
        val output = root.resolve("received")
        assertEquals(1, transfer(source, output, drop = true))
        assertFalse(Files.exists(output.resolve(source.fileName)))
        assertEquals(3, transfer(source, output))
        assertContentEquals(bytes, Files.readAllBytes(output.resolve(source.fileName)))
    }

    @Test
    fun `каталог с пустыми папками и большими файлами получает финальное подтверждение`() {
        val source = Files.createDirectory(root.resolve("Мой мир"))
        Files.createDirectories(source.resolve("empty"))
        Files.write(source.resolve("level.dat"), ByteArray(700_000) { it.toByte() })
        Files.write(source.resolve("empty.txt"), byteArrayOf())
        val sender = ResumableDirectorySender(source)
        val output = root.resolve("tree-output")
        pair { a, b, _ ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit { b.use { TransferReceiver().receive(it, output) } }
                sender.send(a)
                received.get(10, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
        val target = output.resolve(source.fileName)
        assertTrue(Files.isDirectory(target.resolve("empty")))
        assertContentEquals(Files.readAllBytes(source.resolve("level.dat")), Files.readAllBytes(target.resolve("level.dat")))
        assertEquals(0L, Files.size(target.resolve("empty.txt")))
    }
}
