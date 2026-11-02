package org.enderlink.transfer

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

class FileReceiver {
    fun receive(connection: Connection, directory: Path, progress: (Long, Long) -> Unit = { _, _ -> }): Path {
        return receiveHeader(connection, directory, Protocol.read(connection), progress)
    }

    internal fun receiveHeader(
        connection: Connection, directory: Path, header: Pair<Int, ByteArray>,
        progress: (Long, Long) -> Unit,
    ): Path {
        val (type, body) = header
        if (type != Protocol.HEADER) throw IOException("Ожидается описание файла")
        val (name, size) = Protocol.parseHeader(body)
        TransferPaths.validateName(name)
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        val target = root.resolve(name)
        if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Файл уже существует: $target")
        val temporary = Files.createTempFile(root, ".enderlink-", ".part")
        try {
            Files.newOutputStream(temporary).use { output ->
                Protocol.send(connection, Protocol.READY)
                val hash = MessageDigest.getInstance("SHA-256")
                var received = 0L
                progress(received, size)
                while (true) {
                    val (messageType, data) = Protocol.read(connection)
                    when (messageType) {
                        Protocol.DATA -> {
                            if (data.isEmpty() || data.size > Protocol.BLOCK_SIZE || data.size.toLong() > size - received) {
                                throw IOException("Недопустимый блок данных")
                            }
                            output.write(data)
                            hash.update(data)
                            received += data.size
                            progress(received, size)
                        }
                        Protocol.END -> {
                            if (received != size || data.size != 32 || !MessageDigest.isEqual(hash.digest(), data)) {
                                throw IOException("Размер или SHA-256 файла не совпадает")
                            }
                            break
                        }
                        else -> throw IOException("Неожиданное сообщение при приёме файла")
                    }
                }
            }
            // Без REPLACE_EXISTING: существующий файл не перезаписываем.
            Files.move(temporary, target)
            Protocol.send(connection, Protocol.SUCCESS)
            return target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
