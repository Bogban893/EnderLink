package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.world.WorldEntryType
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldConflictException
import java.io.IOException

data class WorldTransferStats(val versionId: String, val transferredBytes: Long, val reusedBytes: Long)

/** Один обмен версией поверх любого Connection. Повторный запуск использует сохранённые объекты. */
object WorldTransfer {
    fun send(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }): WorldTransferStats {
        val manifest = repository.current() ?: throw IOException("Сначала опубликуйте мир")
        val files = manifest.entries.filter { it.type == WorldEntryType.FILE }.distinctBy { it.hash }
        val total = files.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
        progress(0, total)
        WorldTransferProtocol.sendManifest(connection, manifest)
        val (answer, body) = WorldTransferProtocol.read(connection)
        if (body.isNotEmpty()) throw IOException("Некорректный ответ получателя")
        if (answer == WorldTransferProtocol.SAME) {
            progress(total, total)
            return WorldTransferStats(manifest.versionId, 0, total)
        }
        if (answer != WorldTransferProtocol.READY) throw IOException("Получатель не готов принять мир")
        var sent = 0L
        var reused = 0L
        for (entry in files) {
            val (reply, extra) = WorldTransferProtocol.read(connection)
            if (extra.isNotEmpty()) throw IOException("Некорректный запрос объекта")
            when (reply) {
                WorldTransferProtocol.HAVE -> reused += entry.size
                WorldTransferProtocol.NEED -> {
                    repository.openObject(entry).use { input ->
                        val buffer = ByteArray(WorldTransferProtocol.BLOCK_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            WorldTransferProtocol.send(connection, WorldTransferProtocol.DATA, buffer.copyOf(count))
                            sent = Math.addExact(sent, count.toLong())
                            progress(sent + reused, total)
                        }
                    }
                    WorldTransferProtocol.send(connection, WorldTransferProtocol.END)
                    WorldTransferProtocol.expect(connection, WorldTransferProtocol.STORED)
                }
                else -> throw IOException("Неожиданный запрос объекта")
            }
            progress(sent + reused, total)
        }
        WorldTransferProtocol.expect(connection, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, sent, reused)
    }

    fun receive(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }): WorldTransferStats {
        try { return receiveChecked(repository, connection, progress) }
        catch (error: Exception) { WorldTransferProtocol.sendError(connection, error); throw error }
    }

    private fun receiveChecked(repository: WorldRepository, connection: Connection, progress: (Long, Long) -> Unit): WorldTransferStats {
        val manifest = WorldTransferProtocol.receiveManifest(connection)
        val current = repository.current()
        if (current?.versionId == manifest.versionId) {
            repository.verifyCurrent()
            WorldTransferProtocol.send(connection, WorldTransferProtocol.SAME)
            return WorldTransferStats(manifest.versionId, 0, 0)
        }
        if (current != null && current.versionId != manifest.parentVersionId) {
            throw WorldConflictException(current.versionId, manifest.versionId)
        }
        repository.record(manifest)
        val files = manifest.entries.filter { it.type == WorldEntryType.FILE }.distinctBy { it.hash }
        val total = files.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
        progress(0, total)
        WorldTransferProtocol.send(connection, WorldTransferProtocol.READY)
        var received = 0L
        var reused = 0L
        for (entry in files) {
            if (repository.hasObject(entry)) {
                reused = Math.addExact(reused, entry.size)
                WorldTransferProtocol.send(connection, WorldTransferProtocol.HAVE)
            } else {
                WorldTransferProtocol.send(connection, WorldTransferProtocol.NEED)
                repository.storeObject(entry, WorldTransferProtocol.ObjectInput(connection))
                received = Math.addExact(received, entry.size)
                WorldTransferProtocol.send(connection, WorldTransferProtocol.STORED)
            }
            progress(received + reused, total)
        }
        repository.activate(manifest)
        WorldTransferProtocol.send(connection, WorldTransferProtocol.COMPLETE)
        return WorldTransferStats(manifest.versionId, received, reused)
    }
}
