package org.enderlink.desktop.transfer

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.minecraft.MinecraftSaves
import org.enderlink.network.RemoteTransferException
import org.enderlink.network.TransferRejectedException
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransferStats
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(20)
class DesktopOperationsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `publication failure stays distinct from a network failure`() {
        val repository = WorldRepository(root.resolve("repository"))
        val failure = assertFailsWith<WorldPublicationException> {
            DesktopOperations(root.resolve("profile")).send(repository, root.resolve("missing-world"),
                TransferTarget(Route.LOCAL, host = "127.0.0.1", port = 1), {}, { _, _ -> })
        }
        assertTrue(failure.cause != null)
    }

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
        val approved = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val incoming = executor.submit<WorldTransferStats> {
                DesktopOperations(root.resolve("receiver-profile")).apply { begin() }.receive(
                    receiver, TransferTarget(Route.LOCAL, port = port),
                    { if (it == messages.text("transfer.waiting", port.toString())) waiting.countDown() }, { _, _ -> },
                ) { offered, peerId ->
                    assertTrue(offered.entries.isNotEmpty())
                    assertEquals(null, peerId)
                    approved.set(true)
                    true
                }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val stats = DesktopOperations(root.resolve("sender-profile")).apply { begin() }.send(
                sender, source, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = port), {}, { _, _ -> },
            )
            assertTrue(stats.transferredBytes >= 300_000)
            assertEquals(sender.current()!!.versionId, incoming.get(5, TimeUnit.SECONDS).versionId)
            assertTrue(approved.get())
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
                    { if (it == messages.text("transfer.waiting", nextPort.toString())) waitingAgain.countDown() }, { _, _ -> },
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
                        { if (it == messages.text("transfer.waiting", port.toString())) waiting.countDown() }, { _, _ -> })
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
    fun `desktop receiver can decline a proposed version`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "example world")
        val sender = WorldRepository(root.resolve("sender"))
        sender.publish(source)
        val receiver = WorldRepository(root.resolve("receiver"))
        val port = ServerSocket(0).use { it.localPort }
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val incoming = executor.submit<Result<WorldTransferStats>> {
                runCatching {
                    DesktopOperations(root.resolve("receiver-profile")).apply { begin() }.receive(
                        receiver, TransferTarget(Route.LOCAL, port = port),
                        { if (it == messages.text("transfer.waiting", port.toString())) waiting.countDown() },
                        { _, _ -> },
                    ) { offered, peerId ->
                        assertEquals(sender.current()!!.versionId, offered.versionId)
                        assertEquals(null, peerId)
                        false
                    }
                }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFailsWith<RemoteTransferException> {
                DesktopOperations(root.resolve("sender-profile")).apply { begin() }.send(
                    sender, null, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = port), {}, { _, _ -> })
            }
            assertTrue(incoming.get(5, TimeUnit.SECONDS).exceptionOrNull() is TransferRejectedException)
            assertEquals(null, receiver.current())
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `received version can be forwarded from B to C with player files intact`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "world")
        val playerA = "123e4567-e89b-12d3-a456-426614174000"
        val playerB = "123e4567-e89b-12d3-a456-426614174001"
        val playerData = Files.createDirectory(source.resolve("playerdata"))
        Files.writeString(playerData.resolve("$playerA.dat"), "A inventory")
        Files.writeString(playerData.resolve("$playerB.dat"), "B inventory")
        val a = WorldRepository(root.resolve("A"))
        val b = WorldRepository(root.resolve("B"))
        val c = WorldRepository(root.resolve("C"))
        val sender = DesktopOperations(root.resolve("sender-profile"))
        val receiver = DesktopOperations(root.resolve("receiver-profile"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            fun forward(from: WorldRepository, to: WorldRepository, sourcePath: Path?) {
                val port = ServerSocket(0).use { it.localPort }
                val waiting = CountDownLatch(1)
                receiver.begin()
                val incoming = executor.submit<WorldTransferStats> {
                    receiver.receive(to, TransferTarget(Route.LOCAL, port = port),
                        { if (it == messages.text("transfer.waiting", port.toString())) waiting.countDown() }, { _, _ -> })
                }
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                sender.begin()
                sender.send(from, sourcePath, TransferTarget(Route.LOCAL, host = "127.0.0.1", port = port),
                    {}, { _, _ -> })
                incoming.get(5, TimeUnit.SECONDS)
            }
            forward(a, b, source)
            forward(b, c, null)
            assertEquals(a.current()!!.worldId, c.current()!!.worldId)
            assertEquals(a.current()!!.versionId, b.current()!!.versionId)
            assertEquals(b.current()!!.versionId, c.current()!!.versionId)
            for (player in listOf(playerA, playerB)) {
                assertTrue(Files.readAllBytes(a.workingDirectory.resolve("playerdata/$player.dat")).contentEquals(
                    Files.readAllBytes(c.workingDirectory.resolve("playerdata/$player.dat"))))
            }
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
                    { if (it == messages.text("transfer.waiting", port.toString())) waiting.countDown() }, { _, _ -> },
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
