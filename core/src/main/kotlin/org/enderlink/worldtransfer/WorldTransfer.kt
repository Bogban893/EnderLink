package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.network.TransferRejectedException
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldConflictException
import java.io.IOException
import java.net.SocketTimeoutException

data class WorldTransferStats(
    val versionId: String, val transferredBytes: Long, val reusedBytes: Long,
    val wireSentBytes: Long = 0, val wireReceivedBytes: Long = 0,
    val parentFingerprint: ParentFingerprint? = null,
)

/** Hash of the preceding world, ignoring Minecraft's volatile session.lock. */
data class ParentFingerprint(val versionId: String, val formatVersion: Int, val contentHash: String)

enum class WorldTransferPhase { MANIFEST, READINESS, OBJECTS, ACTIVATION, CONFIRMATION }

/** Read timeout at a known transfer stage; no overall world-transfer deadline is imposed. */
class WorldTransferTimeoutException(val phase: WorldTransferPhase, cause: SocketTimeoutException) :
    SocketTimeoutException("Истекло ожидание на этапе $phase") {
    init { initCause(cause) }
}

private class CountedConnection(private val delegate: Connection) : Connection {
    var sentBytes = 0L
        private set
    var receivedBytes = 0L
        private set
    override fun send(payload: ByteArray) {
        delegate.send(payload)
        sentBytes = Math.addExact(sentBytes, payload.size.toLong() + 4)
    }
    override fun receive(): ByteArray? = delegate.receive()?.also {
        receivedBytes = Math.addExact(receivedBytes, it.size.toLong() + 4)
    }
    override fun close() = delegate.close()
}

/** Один обмен версией поверх любого Connection. Повторный запуск использует сохранённые объекты. */
object WorldTransfer {
    fun send(
        repository: WorldRepository, connection: Connection,
        progress: (Long, Long) -> Unit = { _, _ -> },
        phase: (WorldTransferPhase) -> Unit = {},
    ): WorldTransferStats {
        val measured = CountedConnection(connection)
        var currentPhase = WorldTransferPhase.MANIFEST
        fun report(next: WorldTransferPhase) { currentPhase = next; phase(next) }
        try {
            return sendChecked(repository, measured, progress, ::report)
        } catch (error: SocketTimeoutException) {
            throw WorldTransferTimeoutException(currentPhase, error)
        }
    }

    private fun sendChecked(
        repository: WorldRepository, measured: CountedConnection,
        progress: (Long, Long) -> Unit, phase: (WorldTransferPhase) -> Unit,
    ): WorldTransferStats {
        val manifest = repository.current() ?: throw IOException("Сначала опубликуйте мир")
        val objects = manifest.objects()
        val total = objects.fold(0L) { sum, ref -> Math.addExact(sum, ref.size) }
        phase(WorldTransferPhase.MANIFEST)
        WorldTransferProtocol.sendManifest(measured, manifest)
        val parent = manifest.parentVersionId?.let { runCatching { repository.load(it) }.getOrNull() }
        WorldTransferProtocol.sendParentHint(measured, parent)
        phase(WorldTransferPhase.READINESS)
        val (answer, body) = WorldTransferProtocol.read(measured)
        if (body.isNotEmpty()) throw IOException("Некорректный ответ получателя")
        if (answer == WorldTransferProtocol.SAME) {
            progress(total, total)
            return WorldTransferStats(manifest.versionId, 0, total, measured.sentBytes, measured.receivedBytes)
        }
        if (answer != WorldTransferProtocol.READY) throw IOException("Получатель не готов принять мир")
        phase(WorldTransferPhase.OBJECTS)
        progress(0, total)
        var sent = 0L
        var reused = 0L
        for (batch in objects.chunked(WorldTransferProtocol.OBJECTS_PER_BATCH)) {
            val needed = WorldTransferProtocol.readRequests(measured, batch.size)
            for ((index, ref) in batch.withIndex()) {
                if (!needed[index]) {
                    reused = Math.addExact(reused, ref.size)
                } else {
                    repository.openObject(ref).use { input ->
                        val buffer = ByteArray(WorldTransferProtocol.BLOCK_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            WorldTransferProtocol.send(measured, WorldTransferProtocol.DATA, buffer.copyOf(count))
                            sent = Math.addExact(sent, count.toLong())
                            progress(sent + reused, total)
                        }
                    }
                    WorldTransferProtocol.send(measured, WorldTransferProtocol.END)
                }
                progress(sent + reused, total)
            }
            WorldTransferProtocol.expect(measured, WorldTransferProtocol.STORED_BATCH)
        }
        phase(WorldTransferPhase.CONFIRMATION)
        WorldTransferProtocol.expect(measured, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, sent, reused, measured.sentBytes, measured.receivedBytes)
    }

    fun receive(
        repository: WorldRepository, connection: Connection,
        progress: (Long, Long) -> Unit = { _, _ -> },
        approve: (WorldManifest) -> Boolean = { true },
        phase: (WorldTransferPhase) -> Unit = {},
    ): WorldTransferStats {
        val measured = CountedConnection(connection)
        var currentPhase = WorldTransferPhase.MANIFEST
        fun report(next: WorldTransferPhase) { currentPhase = next; phase(next) }
        try { return receiveChecked(repository, measured, progress, ::report, approve).copy(wireSentBytes = measured.sentBytes, wireReceivedBytes = measured.receivedBytes) }
        catch (error: SocketTimeoutException) { throw WorldTransferTimeoutException(currentPhase, error) }
        catch (error: Exception) { WorldTransferProtocol.sendError(measured, error); throw error }
    }

    private fun receiveChecked(
        repository: WorldRepository, connection: Connection,
        progress: (Long, Long) -> Unit, phase: (WorldTransferPhase) -> Unit,
        approve: (WorldManifest) -> Boolean,
    ): WorldTransferStats {
        phase(WorldTransferPhase.MANIFEST)
        val manifest = WorldTransferProtocol.receiveManifest(connection)
        val parentFingerprint = WorldTransferProtocol.readParentHint(connection, manifest)
        if (!approve(manifest)) throw TransferRejectedException()
        val current = repository.current()
        if (current?.versionId == manifest.versionId) {
            repository.verifyCurrent()
            WorldTransferProtocol.send(connection, WorldTransferProtocol.SAME)
            return WorldTransferStats(manifest.versionId, 0, 0, parentFingerprint = parentFingerprint)
        }
        val conflict = current != null && current.versionId != manifest.parentVersionId
        repository.record(manifest)
        val objects = manifest.objects()
        val total = objects.fold(0L) { sum, ref -> Math.addExact(sum, ref.size) }
        WorldTransferProtocol.send(connection, WorldTransferProtocol.READY)
        phase(WorldTransferPhase.OBJECTS)
        progress(0, total)
        var received = 0L
        var reused = 0L
        for (batch in objects.chunked(WorldTransferProtocol.OBJECTS_PER_BATCH)) {
            val needed = BooleanArray(batch.size) { !repository.hasObject(batch[it]) }
            WorldTransferProtocol.sendRequests(connection, needed)
            for ((index, ref) in batch.withIndex()) {
                if (needed[index]) {
                    repository.storeObject(ref, WorldTransferProtocol.ObjectInput(connection))
                    received = Math.addExact(received, ref.size)
                } else {
                    reused = Math.addExact(reused, ref.size)
                }
                progress(received + reused, total)
            }
            WorldTransferProtocol.send(connection, WorldTransferProtocol.STORED_BATCH)
        }
        if (conflict) throw WorldConflictException(current.versionId, manifest.versionId)
        phase(WorldTransferPhase.ACTIVATION)
        repository.activate(manifest)
        WorldTransferProtocol.send(connection, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, received, reused, parentFingerprint = parentFingerprint)
    }
}
