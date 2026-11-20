package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.TransferPaths
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** Нумерованные .part — проверенные блоки; незавершённый блок никогда не используется. */
class ResumableFileReceiver {
    internal fun receiveHeader(
        connection: Connection, directory: Path, body: ByteArray,
        progress: (Long, Long) -> Unit, stateDirectory: Path = directory,
    ): Path {
        val header = ResumeProtocol.parseHeader(body)
        TransferPaths.validateName(header.name)
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        val target = root.resolve(header.name)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.size(target) != header.size ||
                !MessageDigest.isEqual(hashFile(target, connection), header.hash)) {
                throw IOException("Файл уже существует и отличается: $target")
            }
            progress(header.size, header.size)
            Protocol.send(connection, ResumeProtocol.COMPLETE)
            return target
        }
        Files.createDirectories(stateDirectory)
        val stateRoot = stateDirectory.toRealPath()
        val key = ResumeProtocol.hex(ResumeProtocol.digest(body))
        val cache = stateRoot.resolve(".enderlink-resume-$key")
        if (!Files.exists(cache, NOFOLLOW_LINKS)) Files.createDirectory(cache)
        if (!Files.isDirectory(cache, NOFOLLOW_LINKS)) throw IOException("Некорректный каталог докачки")
        // Общая блокировка на каталог получения: не даёт двум сессиям писать
        // один результат или удалять состояние во время докачки.
        FileChannel.open(stateRoot.resolve(".enderlink-resume.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { lockChannel ->
            val lock = try { lockChannel.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
                ?: throw IOException("В этом каталоге уже идёт докачка")
            lock.use {
                // Неполные записи после аварии не участвуют в докачке.
                for (pattern in listOf("block-*.tmp", "assembled-*.tmp", "manifest-*.tmp")) {
                    Files.newDirectoryStream(cache, pattern).use { paths ->
                        paths.forEach { Files.deleteIfExists(it) }
                    }
                }
                val metadata = cache.resolve("manifest")
                if (Files.exists(metadata, NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(metadata, NOFOLLOW_LINKS) || Files.size(metadata) != body.size.toLong() ||
                        !Files.readAllBytes(metadata).contentEquals(body)) {
                        throw IOException("Повреждены метаданные докачки")
                    }
                } else {
                    val pending = Files.createTempFile(cache, "manifest-", ".tmp")
                    try {
                        FileChannel.open(pending, WRITE).use { output ->
                            val buffer = java.nio.ByteBuffer.wrap(body)
                            while (buffer.hasRemaining()) output.write(buffer)
                            output.force(true)
                        }
                        Files.move(pending, metadata)
                    } finally { Files.deleteIfExists(pending) }
                }
                Protocol.send(connection, Protocol.READY)
                val hashes = mutableListOf<ByteArray>()
                var done = 0L
                var index = 0
                progress(0, header.size)
                while (done < header.size) {
                    val (type, hash) = Protocol.read(connection)
                    if (type != ResumeProtocol.BLOCK || hash.size != 32) throw IOException("Ожидается хеш блока")
                    val length = minOf(header.size - done, ResumeProtocol.BLOCK_SIZE.toLong()).toInt()
                    val block = cache.resolve("$index.part")
                    val valid = Files.isRegularFile(block, NOFOLLOW_LINKS) && Files.size(block) == length.toLong() &&
                        MessageDigest.isEqual(ResumeProtocol.digest(Files.readAllBytes(block)), hash)
                    Protocol.send(connection, if (valid) ResumeProtocol.HAVE else ResumeProtocol.NEED)
                    if (!valid) {
                        val (dataType, data) = Protocol.read(connection)
                        if (dataType != Protocol.DATA || data.size != length ||
                            !MessageDigest.isEqual(ResumeProtocol.digest(data), hash)) throw IOException("Повреждён блок файла")
                        val temporary = Files.createTempFile(cache, "block-", ".tmp")
                        try {
                            FileChannel.open(temporary, WRITE).use { output ->
                                val buffer = java.nio.ByteBuffer.wrap(data)
                                while (buffer.hasRemaining()) output.write(buffer)
                                output.force(true)
                            }
                            Files.move(temporary, block, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(temporary) }
                        Protocol.send(connection, Protocol.READY)
                    }
                    hashes.add(hash)
                    done += length
                    index++
                    progress(done, header.size)
                }
                val (endType, endHash) = Protocol.read(connection)
                if (endType != Protocol.END || !MessageDigest.isEqual(endHash, header.hash)) throw IOException("Некорректное завершение файла")
                val assembled = Files.createTempFile(cache, "assembled-", ".tmp")
                try {
                    val fullHash = MessageDigest.getInstance("SHA-256")
                    Files.newOutputStream(assembled).use { output ->
                        for ((number, expected) in hashes.withIndex()) {
                            val bytes = Files.readAllBytes(cache.resolve("$number.part"))
                            if (!MessageDigest.isEqual(ResumeProtocol.digest(bytes), expected)) throw IOException("Сохранённый блок повреждён")
                            output.write(bytes)
                            fullHash.update(bytes)
                            Protocol.send(connection, ResumeProtocol.VERIFYING)
                        }
                    }
                    if (!MessageDigest.isEqual(fullHash.digest(), header.hash)) throw IOException("SHA-256 файла не совпадает")
                    Files.move(assembled, target)
                } finally { Files.deleteIfExists(assembled) }
                // Не удаляем неизвестные файлы; только состояние этой версии.
                repeat(index) { Files.deleteIfExists(cache.resolve("$it.part")) }
                Files.delete(metadata)
                // После аварийного завершения могут остаться временные файлы.
                val empty = Files.list(cache).use { leftovers -> leftovers.findAny().isEmpty }
                if (empty) Files.delete(cache)
                Protocol.send(connection, Protocol.SUCCESS)
                return target
            }
        }
    }

    private fun hashFile(path: Path, connection: Connection): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(ResumeProtocol.BLOCK_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                hash.update(buffer, 0, count)
                Protocol.send(connection, ResumeProtocol.VERIFYING)
            }
        }
        return hash.digest()
    }
}
