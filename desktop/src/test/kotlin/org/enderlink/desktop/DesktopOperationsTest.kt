package org.enderlink.desktop

import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransferStats
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(20)
class DesktopOperationsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `quick send publishes a closed world and receives it over tcp`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "example world")
        Files.createDirectory(source.resolve("region"))
        Files.write(source.resolve("region/r.0.0.mca"), ByteArray(300_000) { (it % 127).toByte() })
        val sender = WorldRepository(root.resolve("sender"))
        val receiver = WorldRepository(root.resolve("receiver"))
        val port = ServerSocket(0).use { it.localPort }
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val incoming = executor.submit<WorldTransferStats> {
                DesktopOperations(root.resolve("receiver-profile")).apply { begin() }.receive(
                    receiver, TransferTarget(Route.LOCAL, port = port),
                    { if (it.startsWith("Ожидание подключения")) waiting.countDown() }, { _, _ -> },
                )
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val stats = DesktopOperations(root.resolve("sender-profile")).apply { begin() }.send(
                sender, source, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = port), {}, { _, _ -> },
            )
            assertTrue(stats.transferredBytes >= 300_000)
            assertEquals(sender.current()!!.versionId, incoming.get(5, TimeUnit.SECONDS).versionId)
            assertTrue(Files.readAllBytes(source.resolve("region/r.0.0.mca")).contentEquals(
                Files.readAllBytes(receiver.workingDirectory.resolve("region/r.0.0.mca")),
            ))
            val saves = root.resolve("minecraft/saves")
            val installed = MinecraftSaves.install(receiver, saves, "World")
            assertEquals("example world", Files.readString(installed.directory.resolve("level.dat")))

            Files.writeString(source.resolve("level.dat"), "updated world")
            val nextPort = ServerSocket(0).use { it.localPort }
            val waitingAgain = CountDownLatch(1)
            val nextIncoming = executor.submit<WorldTransferStats> {
                DesktopOperations(root.resolve("receiver-profile")).apply { begin() }.receive(
                    receiver, TransferTarget(Route.LOCAL, port = nextPort),
                    { if (it.startsWith("Ожидание подключения")) waitingAgain.countDown() }, { _, _ -> },
                )
            }
            assertTrue(waitingAgain.await(5, TimeUnit.SECONDS))
            val delta = DesktopOperations(root.resolve("sender-profile")).apply { begin() }.send(
                sender, source, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = nextPort), {}, { _, _ -> },
            )
            nextIncoming.get(5, TimeUnit.SECONDS)
            assertTrue(delta.transferredBytes < stats.transferredBytes)
            assertTrue(delta.reusedBytes >= 300_000)
            MinecraftSaves.install(receiver, saves, "World")
            assertEquals("updated world", Files.readString(installed.directory.resolve("level.dat")))
            assertFalse(Files.exists(source.resolve(".enderlink")), "Source is left untouched")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `cancel closes waiting tcp listener`() {
        val receiver = WorldRepository(root.resolve("receiver"))
        val operations = DesktopOperations(root.resolve("profile"))
        operations.begin()
        val port = ServerSocket(0).use { it.localPort }
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val incoming = executor.submit<Result<WorldTransferStats>> {
                runCatching {
                    operations.receive(receiver, TransferTarget(Route.LOCAL, port = port),
                        { if (it.startsWith("Ожидание подключения")) waiting.countDown() }, { _, _ -> })
                }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            operations.cancel()
            assertTrue(incoming.get(5, TimeUnit.SECONDS).isFailure)
            assertTrue(operations.wasCancelled)
            assertEquals(null, receiver.current())
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `preexisting minecraft save reuses blocks and is matched to sent parent`() {
        val source = Files.createDirectory(root.resolve("source"))
        val region = source.resolve("region.mca")
        Files.writeString(source.resolve("level.dat"), "same world")
        Files.write(region, ByteArray(2 * 256 * 1024) { (it / (256 * 1024)).toByte() })
        val sender = WorldRepository(root.resolve("sender"))
        sender.publish(source)
        val saves = Files.createDirectories(root.resolve("minecraft/saves"))
        val existing = Files.createDirectory(saves.resolve("Existing World"))
        Files.copy(source.resolve("level.dat"), existing.resolve("level.dat"))
        Files.copy(region, existing.resolve("region.mca"))
        Files.writeString(existing.resolve("session.lock"), "old local lock")

        val changed = Files.readAllBytes(region)
        changed[0] = 42
        Files.write(region, changed)
        sender.publish(source)
        val receiver = WorldRepository(root.resolve("receiver"))
        assertTrue(MinecraftSaves.cacheExisting(receiver, saves, "Existing World") >= 2)

        val port = ServerSocket(0).use { it.localPort }
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val incoming = executor.submit<WorldTransferStats> {
                DesktopOperations(root.resolve("receiver-profile")).apply { begin() }.receive(
                    receiver, TransferTarget(Route.LOCAL, port = port),
                    { if (it.startsWith("Ожидание подключения")) waiting.countDown() }, { _, _ -> },
                )
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val sent = DesktopOperations(root.resolve("sender-profile")).apply { begin() }.send(
                sender, null, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = port), {}, { _, _ -> },
            )
            val received = incoming.get(5, TimeUnit.SECONDS)
            assertTrue(sent.reusedBytes >= 256 * 1024)
            assertTrue(sent.transferredBytes <= 256 * 1024)
            assertTrue(received.parentFingerprint != null)
            val installed = MinecraftSaves.install(receiver, saves, "Existing World", received.parentFingerprint)
            assertEquals("Existing World", installed.directory.fileName.toString())
            assertTrue(changed.contentEquals(Files.readAllBytes(existing.resolve("region.mca"))))
        } finally { executor.shutdownNow() }
    }
}
