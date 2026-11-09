package org.enderlink.network.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.runBlocking
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransfer
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(30)
class WorldIrohTransferTest {
    @TempDir lateinit var root: Path

    @Test
    fun `версии мира передаются по iroh и обновляются`() {
        val sender = WorldRepository(root.resolve("sender"))
        val receiver = WorldRepository(root.resolve("receiver"))
        Files.writeString(Files.createDirectories(sender.workingDirectory).resolve("level.dat"), "first")
        Files.createDirectory(sender.workingDirectory.resolve("empty"))
        sender.publish(sender.workingDirectory)

        IrohProfile.open(root.resolve("sender-profile")).use { senderProfile ->
            IrohProfile.open(root.resolve("receiver-profile")).use { receiverProfile ->
                senderProfile.add("receiver", receiverProfile.id)
                receiverProfile.add("sender", senderProfile.id)
                val senderEndpoint = runBlocking { Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = senderProfile.key, alpns = listOf(IROH_ALPN))) }
                val receiverEndpoint = runBlocking { Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = receiverProfile.key, alpns = listOf(IROH_ALPN))) }
                IrohNode(senderEndpoint, senderProfile, {}).use { senderNode ->
                    IrohNode(receiverEndpoint, receiverProfile, {}).use { receiverNode ->
                        fun exchange(): Long {
                            val executor = Executors.newSingleThreadExecutor()
                            try {
                                val accepting = executor.submit {
                                    receiverNode.accept(IrohPurpose.TRANSFER).use { connection ->
                                        connection.readTimeoutMs = 10_000
                                        WorldTransfer.receive(receiver, connection)
                                    }
                                }
                                val native = runBlocking { senderEndpoint.connect(receiverEndpoint.addr(), IROH_ALPN) }
                                val stats = runBlocking { senderNode.prepare(native, IrohPurpose.TRANSFER, true) }.use { connection ->
                                    connection.readTimeoutMs = 10_000
                                    WorldTransfer.send(sender, connection)
                                }
                                accepting.get(10, TimeUnit.SECONDS)
                                return stats.transferredBytes
                            } finally { executor.shutdownNow() }
                        }

                        assertTrue(exchange() > 0)
                        assertEquals(0, exchange())
                        Files.writeString(sender.workingDirectory.resolve("level.dat"), "second")
                        sender.publish(sender.workingDirectory)
                        assertEquals(6, exchange())
                        assertEquals(sender.current()!!.versionId, receiver.current()!!.versionId)
                        assertEquals("second", Files.readString(receiver.workingDirectory.resolve("level.dat")))
                    }
                }
            }
        }
    }
}
