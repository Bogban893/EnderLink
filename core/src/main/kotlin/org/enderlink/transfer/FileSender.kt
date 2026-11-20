package org.enderlink.transfer

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FileSender {
    /** Источник должен оставаться неизменным до конца передачи. */
    fun send(connection: Connection, source: Path, progress: (Long, Long) -> Unit = { _, _ -> }) {
        if (!Files.isRegularFile(source)) throw IOException("Не найден обычный файл: $source")
        Files.newInputStream(source).use { input ->
            val size = Files.size(source)
            Protocol.send(connection, Protocol.HEADER, Protocol.header(source.fileName.toString(), size))
            Protocol.expect(connection, Protocol.READY)
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(Protocol.BLOCK_SIZE)
            var sent = 0L
            progress(sent, size)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                if (count.toLong() > size - sent) throw IOException("Размер исходного файла изменился")
                hash.update(buffer, 0, count)
                Protocol.send(connection, Protocol.DATA, buffer.copyOf(count))
                sent += count
                progress(sent, size)
            }
            if (sent != size) throw IOException("Размер исходного файла изменился")
            Protocol.send(connection, Protocol.END, hash.digest())
            Protocol.expect(connection, Protocol.SUCCESS)
        }
    }
}
