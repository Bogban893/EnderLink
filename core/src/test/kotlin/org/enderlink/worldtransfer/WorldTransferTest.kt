package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldScanner
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystemException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(30)
class WorldTransferTest {
    @TempDir lateinit var root: Path

    private fun repositories(): Pair<WorldRepository, WorldRepository> {
        val sender = WorldRepository(root.resolve("sender"))
        val receiver = WorldRepository(root.resolve("receiver"))
        Files.createDirectories(sender.workingDirectory.resolve("empty/nested"))
        Files.writeString(sender.workingDirectory.resolve("level.dat"), "first")
        Files.write(sender.workingDirectory.resolve("region.mca"), ByteArray(600_000) { (it % 251).toByte() })
        sender.publish(sender.workingDirectory)
        return sender to receiver
    }

    private fun exchange(sender: WorldRepository, receiver: WorldRepository, dropAfterData: Int? = null): WorldTransferStats {
        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 5_000
                    server.readTimeoutMs = 5_000
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val receiving = executor.submit<WorldTransferStats> {
                            if (dropAfterData != null) assertFailsWith<IOException> { WorldTransfer.receive(receiver, server) }
                                .let { WorldTransferStats("failed", 0, 0) }
                            else WorldTransfer.receive(receiver, server)
                        }
                        var dataCount = 0
                        val outgoing = object : Connection by client {
                            override fun send(payload: ByteArray) {
                                if (payload.size > 5 && payload[4].toInt() == WorldTransferProtocol.DATA) {
                                    dataCount++
                                    if (dataCount == dropAfterData) {
                                        client.close()
                                        throw IOException("Имитируем обрыв")
                                    }
                                }
                                client.send(payload)
                            }
                        }
                        val stats = if (dropAfterData != null) {
                            assertFailsWith<IOException> { WorldTransfer.send(sender, outgoing) }
                            WorldTransferStats("failed", 0, 0)
                        } else WorldTransfer.send(sender, outgoing)
                        receiving.get(10, TimeUnit.SECONDS)
                        return stats
                    } finally { executor.shutdownNow() }
                }
            }
        }
    }

    @Test
    fun `первая передача обновление и отсутствие изменений`() {
        val (sender, receiver) = repositories()
        val first = exchange(sender, receiver)
        assertTrue(first.transferredBytes > 600_000)
        assertTrue(first.wireSentBytes > first.transferredBytes)
        assertEquals(sender.current()!!.treeHash, receiver.current()!!.treeHash)
        val unchanged = exchange(sender, receiver)
        assertEquals(0, unchanged.transferredBytes)
        assertTrue(unchanged.wireSentBytes > 0)
        Files.delete(sender.workingDirectory.resolve("level.dat"))
        Files.writeString(sender.workingDirectory.resolve("new.dat"), "second")
        sender.publish(sender.workingDirectory)
        val next = exchange(sender, receiver)
        assertEquals(6, next.transferredBytes)
        assertTrue(next.reusedBytes >= 600_000)
        assertFalse(Files.exists(receiver.workingDirectory.resolve("level.dat")))
        assertTrue(Files.isDirectory(receiver.workingDirectory.resolve("empty/nested")))
        assertEquals(sender.current()!!.treeHash, WorldScanner.scan(receiver.workingDirectory, sender.current()!!.worldId).treeHash)
    }

    @Test
    fun `обрыв не активирует версию и повтор использует проверенный файл`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        val first = receiver.current()!!
        Files.writeString(sender.workingDirectory.resolve("level.dat"), "updated")
        Files.write(sender.workingDirectory.resolve("new-large"), ByteArray(700_000) { (it % 191).toByte() })
        sender.publish(sender.workingDirectory)
        exchange(sender, receiver, dropAfterData = 2)
        assertEquals(first.versionId, receiver.current()!!.versionId)
        val resumed = exchange(sender, receiver)
        assertEquals(700_000, resumed.transferredBytes)
        assertTrue(resumed.reusedBytes >= 600_000)
        assertEquals(sender.current()!!.versionId, receiver.current()!!.versionId)
    }

    @Test
    fun `поврежденный сохраненный объект запрашивается заново`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        val old = receiver.current()!!
        val region = old.entries.single { it.path == "region.mca" }
        Files.writeString(root.resolve("receiver/.enderlink/objects/${region.blocks.first().hash.removePrefix("sha256:")}"), "corrupt")
        Files.writeString(sender.workingDirectory.resolve("level.dat"), "updated")
        sender.publish(sender.workingDirectory)
        val stats = exchange(sender, receiver)
        assertTrue(stats.transferredBytes >= WorldManifest.BLOCK_SIZE)
        assertEquals(sender.current()!!.versionId, receiver.current()!!.versionId)
    }

    @Test
    fun `изменение одного блока большого файла передает только этот блок`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        val first = receiver.current()!!
        val file = sender.workingDirectory.resolve("region.mca")
        val updated = Files.readAllBytes(file)
        updated[WorldManifest.BLOCK_SIZE + 100] = (updated[WorldManifest.BLOCK_SIZE + 100] + 1).toByte()
        Files.write(file, updated)
        val second = sender.publish(sender.workingDirectory)
        assertEquals(first.treeHash != second.treeHash, true)
        val stats = exchange(sender, receiver)
        assertEquals(WorldManifest.BLOCK_SIZE.toLong(), stats.transferredBytes)
        assertTrue(stats.reusedBytes > 300_000)
        assertTrue(updated.contentEquals(Files.readAllBytes(receiver.workingDirectory.resolve("region.mca"))))
        receiver.checkout(first.versionId)
        assertFalse(updated.contentEquals(Files.readAllBytes(receiver.workingDirectory.resolve("region.mca"))))
    }

    @Test
    fun `первое получение принимает текущую версию с историей`() {
        val (sender, receiver) = repositories()
        Files.writeString(sender.workingDirectory.resolve("level.dat"), "second")
        sender.publish(sender.workingDirectory)
        exchange(sender, receiver)
        assertEquals(sender.current()!!.versionId, receiver.current()!!.versionId)
    }

    @Test
    fun `одинаковый ID не скрывает повреждение рабочей копии`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        Files.writeString(receiver.workingDirectory.resolve("level.dat"), "local damage")
        assertFailsWith<IOException> { exchange(sender, receiver) }
        assertEquals("local damage", Files.readString(receiver.workingDirectory.resolve("level.dat")))
    }

    @Test
    fun `конфликт веток не заменяет рабочий мир получателя`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        Files.writeString(sender.workingDirectory.resolve("level.dat"), "sender branch")
        sender.publish(sender.workingDirectory)
        val remote = sender.current()!!
        Files.writeString(receiver.workingDirectory.resolve("level.dat"), "receiver branch")
        val local = receiver.publish(receiver.workingDirectory)
        assertFailsWith<IOException> { exchange(sender, receiver) }
        assertEquals(local.versionId, receiver.current()!!.versionId)
        assertEquals("receiver branch", Files.readString(receiver.workingDirectory.resolve("level.dat")))
        assertEquals(remote, receiver.load(remote.versionId))
        receiver.checkout(remote.versionId)
        assertEquals("sender branch", Files.readString(receiver.workingDirectory.resolve("level.dat")))
    }

    @Test
    fun `отказ записи объекта сохраняет старую версию и позволяет повторить передачу`() {
        val (sender, receiver) = repositories()
        exchange(sender, receiver)
        val previous = receiver.current()!!
        Files.write(sender.workingDirectory.resolve("new-large"), ByteArray(300_000) { (it % 199).toByte() })
        sender.publish(sender.workingDirectory)
        val receiverRoot = root.resolve("receiver")
        val failing = WorldRepository(receiverRoot) { path ->
            val output = Files.newOutputStream(path)
            object : OutputStream() {
                var written = 0
                override fun write(value: Int) {
                    if (written >= 32_768) throw FileSystemException(path.toString(), null, "No space left on device")
                    output.write(value)
                    written++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    val allowed = minOf(length, 32_768 - written)
                    if (allowed > 0) output.write(bytes, offset, allowed)
                    written += allowed
                    if (allowed < length) throw FileSystemException(path.toString(), null, "No space left on device")
                }
                override fun close() = output.close()
            }
        }
        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 5_000
                    server.readTimeoutMs = 5_000
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val receiving = executor.submit<Throwable?> {
                            runCatching { WorldTransfer.receive(failing, server) }.exceptionOrNull()
                        }
                        assertFailsWith<IOException> { WorldTransfer.send(sender, client) }
                        assertTrue(receiving.get(10, TimeUnit.SECONDS) is IOException)
                    } finally { executor.shutdownNow() }
                }
            }
        }
        assertEquals(previous.versionId, receiver.current()!!.versionId)
        assertFalse(Files.exists(receiver.workingDirectory.resolve("new-large")))
        Files.list(receiverRoot.resolve(".enderlink/objects")).use { files ->
            assertEquals(0L, files.filter { it.fileName.toString().startsWith("incoming-") }.count())
        }
        exchange(sender, receiver)
        assertEquals(sender.current()!!.versionId, receiver.current()!!.versionId)
    }
}
