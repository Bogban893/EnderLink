package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldConflictException
import java.io.IOException

data class WorldTransferStats(
    val versionId: String, val transferredBytes: Long, val reusedBytes: Long,
    val wireSentBytes: Long = 0, val wireReceivedBytes: Long = 0,
)

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
    fun send(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }): WorldTransferStats {
        val measured = CountedConnection(connection)
        val manifest = repository.current() ?: throw IOException("Сначала опубликуйте мир")
        val objects = manifest.objects()
        val total = objects.fold(0L) { sum, ref -> Math.addExact(sum, ref.size) }
        progress(0, total)
        WorldTransferProtocol.sendManifest(measured, manifest)
        val (answer, body) = WorldTransferProtocol.read(measured)
        if (body.isNotEmpty()) throw IOException("Некорректный ответ получателя")
        if (answer == WorldTransferProtocol.SAME) {
            progress(total, total)
            return WorldTransferStats(manifest.versionId, 0, total, measured.sentBytes, measured.receivedBytes)
        }
        if (answer != WorldTransferProtocol.READY) throw IOException("Получатель не готов принять мир")
        var sent = 0L
        var reused = 0L
        for (ref in objects) {
            val (reply, extra) = WorldTransferProtocol.read(measured)
            if (extra.isNotEmpty()) throw IOException("Некорректный запрос объекта")
            when (reply) {
                WorldTransferProtocol.HAVE -> reused = Math.addExact(reused, ref.size)
                WorldTransferProtocol.NEED -> {
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
                    WorldTransferProtocol.expect(measured, WorldTransferProtocol.STORED)
                }
                else -> throw IOException("Неожиданный запрос объекта")
            }
            progress(sent + reused, total)
        }
        WorldTransferProtocol.expect(measured, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, sent, reused, measured.sentBytes, measured.receivedBytes)
    }

    fun receive(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }): WorldTransferStats {
        val measured = CountedConnection(connection)
        try { return receiveChecked(repository, measured, progress).copy(wireSentBytes = measured.sentBytes, wireReceivedBytes = measured.receivedBytes) }
        catch (error: Exception) { WorldTransferProtocol.sendError(measured, error); throw error }
    }

    private fun receiveChecked(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit): WorldTransferStats {
        val manifest = WorldTransferProtocol.receiveManifest(connection)
        val current = repository.current()
        if (current?.versionId == manifest.versionId) {
            repository.verifyCurrent()
            WorldTransferProtocol.send(connection, WorldTransferProtocol.SAME)
            return WorldTransferStats(manifest.versionId, 0, 0)
        }
        val conflict = current != null && current.versionId != manifest.parentVersionId
        repository.record(manifest)
        val objects = manifest.objects()
        val total = objects.fold(0L) { sum, ref -> Math.addExact(sum, ref.size) }
        progress(0, total)
        WorldTransferProtocol.send(connection, WorldTransferProtocol.READY)
        var received = 0L
        var reused = 0L
        for (ref in objects) {
            if (repository.hasObject(ref)) {
                reused = Math.addExact(reused, ref.size)
                WorldTransferProtocol.send(connection, WorldTransferProtocol.HAVE)
            } else {
                WorldTransferProtocol.send(connection, WorldTransferProtocol.NEED)
                repository.storeObject(ref, WorldTransferProtocol.ObjectInput(connection))
                received = Math.addExact(received, ref.size)
                WorldTransferProtocol.send(connection, WorldTransferProtocol.STORED)
            }
            progress(received + reused, total)
        }
        if (conflict) throw WorldConflictException(current.versionId, manifest.versionId)
        repository.activate(manifest)
        WorldTransferProtocol.send(connection, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, received, reused)
    }
}
