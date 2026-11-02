package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Подготовку выполняют до подключения: вычисление хешей может быть долгим. */
class ResumableFileSender(private val source: Path) {
    internal val size: Long
    internal val hash: ByteArray
    private val blocks = mutableListOf<ByteArray>()

    init {
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) throw IOException("Не найден обычный файл: $source")
        size = Files.size(source)
        val fullHash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(source).use { input ->
            var remaining = size
            while (remaining > 0) {
                val bytes = input.readNBytes(minOf(remaining, ResumeProtocol.BLOCK_SIZE.toLong()).toInt())
                if (bytes.isEmpty()) throw IOException("Исходный файл изменился при сканировании")
                fullHash.update(bytes)
                blocks.add(ResumeProtocol.digest(bytes))
                remaining -= bytes.size
            }
            if (input.read() != -1) throw IOException("Исходный файл изменился при сканировании")
        }
        hash = fullHash.digest()
    }

    fun send(connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }) {
        Protocol.send(connection, ResumeProtocol.HEADER, ResumeProtocol.header(source.fileName.toString(), size, hash))
        Protocol.expect(connection, Protocol.READY)
        RandomAccessFile(source.toFile(), "r").use { input ->
            if (input.length() != size) throw IOException("Исходный файл изменился")
            var done = 0L
            progress(0, size)
            for ((index, blockHash) in blocks.withIndex()) {
                Protocol.send(connection, ResumeProtocol.BLOCK, blockHash)
                val (answer, body) = Protocol.read(connection)
                if (body.isNotEmpty() || answer !in setOf(ResumeProtocol.HAVE, ResumeProtocol.NEED)) {
                    throw IOException("Некорректный запрос блока")
                }
                val length = minOf(size - done, ResumeProtocol.BLOCK_SIZE.toLong()).toInt()
                if (answer == ResumeProtocol.NEED) {
                    input.seek(index.toLong() * ResumeProtocol.BLOCK_SIZE)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    if (!MessageDigest.isEqual(ResumeProtocol.digest(bytes), blockHash)) {
                        throw IOException("Исходный файл изменился: отправка остановлена")
                    }
                    Protocol.send(connection, Protocol.DATA, bytes)
                    Protocol.expect(connection, Protocol.READY)
                }
                done += length
                progress(done, size)
            }
            Protocol.send(connection, Protocol.END, hash)
            ResumeProtocol.expectSuccess(connection)
        }
    }
}
