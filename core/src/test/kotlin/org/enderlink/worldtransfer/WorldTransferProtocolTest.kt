package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.network.protocol.FrameCodec
import org.enderlink.world.WorldBlock
import org.enderlink.world.WorldEntry
import org.enderlink.world.WorldEntryType
import org.enderlink.world.WorldManifest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldTransferProtocolTest {
    @Test
    fun `манифест большого файла помещается в кадры Connection`() {
        val hash = "sha256:" + "a".repeat(64)
        val blockHash = "sha256:" + "b".repeat(64)
        val blocks = List(15_000) { WorldBlock(blockHash, WorldManifest.BLOCK_SIZE) }
        val manifest = WorldManifest.create(
            WorldManifest.newWorldId(), null,
            listOf(WorldEntry("large.mca", WorldEntryType.FILE, 15_000L * WorldManifest.BLOCK_SIZE, hash, blocks)),
        )
        val frames = ArrayDeque<ByteArray>()
        val connection = object : Connection {
            override fun send(payload: ByteArray) {
                require(payload.size <= FrameCodec.DEFAULT_MAX_PAYLOAD)
                frames.addLast(payload)
            }
            override fun receive(): ByteArray? = if (frames.isEmpty()) null else frames.removeFirst()
            override fun close() {}
        }
        WorldTransferProtocol.sendManifest(connection, manifest)
        assertEquals(manifest, WorldTransferProtocol.receiveManifest(connection))
        assertEquals(0, frames.size)
    }
}
