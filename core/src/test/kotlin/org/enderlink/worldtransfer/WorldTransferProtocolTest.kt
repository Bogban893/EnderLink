package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.network.IncompatibleTransferProtocolException
import org.enderlink.network.RemoteTransferException
import org.enderlink.network.TransferFailureKind
import org.enderlink.network.protocol.FrameCodec
import org.enderlink.world.WorldBlock
import org.enderlink.world.WorldEntry
import org.enderlink.world.WorldEntryType
import org.enderlink.world.WorldManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.AccessDeniedException

class WorldTransferProtocolTest {
    @Test
    fun `receiver error includes an optional compatible failure code`() {
        val frames = ArrayDeque<ByteArray>()
        val connection = object : Connection {
            override fun send(payload: ByteArray) { frames.addLast(payload) }
            override fun receive(): ByteArray? = frames.removeFirstOrNull()
            override fun close() {}
        }
        WorldTransferProtocol.sendError(connection, AccessDeniedException("world"))
        val coded = assertFailsWith<RemoteTransferException> { WorldTransferProtocol.read(connection) }
        assertEquals(TransferFailureKind.LOCAL_STORAGE, coded.remoteKind)

        val oldBody = ByteArrayOutputStream().also { body ->
            DataOutputStream(body).use { it.writeUTF("old recipient error") }
        }.toByteArray()
        WorldTransferProtocol.send(connection, WorldTransferProtocol.ERROR, oldBody)
        val legacy = assertFailsWith<RemoteTransferException> { WorldTransferProtocol.read(connection) }
        assertEquals(null, legacy.remoteKind)
        assertEquals("Получатель: old recipient error", legacy.message)

        connection.send(byteArrayOf(0, 0, 0, 0, 1))
        assertFailsWith<IncompatibleTransferProtocolException> { WorldTransferProtocol.read(connection) }
    }

    @Test
    fun `parent fingerprint matches previous version without session lock`() {
        val worldId = WorldManifest.newWorldId()
        val parent = WorldManifest.create(worldId, null, listOf(
            WorldEntry("level.dat", WorldEntryType.FILE, 1, "sha256:" + "a".repeat(64),
                listOf(WorldBlock("sha256:" + "a".repeat(64), 1))),
            WorldEntry("session.lock", WorldEntryType.FILE, 1, "sha256:" + "b".repeat(64),
                listOf(WorldBlock("sha256:" + "b".repeat(64), 1))),
        ))
        val child = WorldManifest.create(worldId, parent.versionId, parent.entries)
        val frames = ArrayDeque<ByteArray>()
        val connection = object : Connection {
            override fun send(payload: ByteArray) { frames.addLast(payload) }
            override fun receive(): ByteArray? = frames.removeFirstOrNull()
            override fun close() {}
        }
        WorldTransferProtocol.sendParentHint(connection, parent)
        val hint = WorldTransferProtocol.readParentHint(connection, child)!!
        assertEquals(parent.versionId, hint.versionId)
        assertEquals(WorldManifest.create(worldId, null, parent.entries.filterNot { it.path == "session.lock" }).treeHash,
            hint.contentHash)
        WorldTransferProtocol.sendParentHint(connection, parent)
        assertFailsWith<IOException> { WorldTransferProtocol.readParentHint(connection, parent) }
    }

    @Test
    fun `групповой запрос передает маску нужных объектов и отклоняет лишние биты`() {
        val frames = ArrayDeque<ByteArray>()
        val connection = object : Connection {
            override fun send(payload: ByteArray) { frames.addLast(payload) }
            override fun receive(): ByteArray? = frames.removeFirstOrNull()
            override fun close() {}
        }
        val needed = booleanArrayOf(true, false, true, true, false, false, true, false)
        WorldTransferProtocol.sendRequests(connection, needed)
        assertContentEquals(needed, WorldTransferProtocol.readRequests(connection, needed.size))
        WorldTransferProtocol.sendRequests(connection, booleanArrayOf(true, false))
        assertFailsWith<IOException> { WorldTransferProtocol.readRequests(connection, 3) }
        WorldTransferProtocol.sendRequests(connection, booleanArrayOf(true, false))
        frames.first()[6] = 0x80.toByte()
        assertFailsWith<IOException> { WorldTransferProtocol.readRequests(connection, 2) }
    }

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
