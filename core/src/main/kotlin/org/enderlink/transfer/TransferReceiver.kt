package org.enderlink.transfer

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import org.enderlink.transfer.resume.DirectoryManifest
import org.enderlink.transfer.resume.ResumableDirectoryReceiver
import org.enderlink.transfer.resume.ResumeProtocol
import org.enderlink.transfer.resume.ResumableFileReceiver
import java.io.IOException
import java.nio.file.Path

/** Выбирает приём файла или каталога по первому сообщению протокола. */
class TransferReceiver {
    fun receive(connection: Connection, directory: Path, progress: (Long, Long) -> Unit = { _, _ -> }): Path =
        receive(connection, directory, null, {}, progress)

    fun receive(
        connection: Connection, directory: Path, expectedOperationId: String?,
        onOperation: (String) -> Unit, progress: (Long, Long) -> Unit,
    ): Path {
        val header = Protocol.read(connection)
        val operationId = when (header.first) {
            DirectoryManifest.HEADER -> {
                DirectoryManifest.decodeHeader(header.second)
                "directory-" + ResumeProtocol.hex(ResumeProtocol.digest(header.second))
            }
            ResumeProtocol.HEADER -> {
                ResumeProtocol.parseHeader(header.second)
                "file-" + ResumeProtocol.hex(ResumeProtocol.digest(header.second))
            }
            else -> null
        }
        if (expectedOperationId != null && operationId != expectedOperationId) {
            throw IOException("Подключение относится к другой операции передачи")
        }
        operationId?.let(onOperation)
        return when (header.first) {
            DirectoryManifest.HEADER -> ResumableDirectoryReceiver().receiveHeader(connection, directory, header.second, progress)
            ResumeProtocol.HEADER -> ResumableFileReceiver().receiveHeader(connection, directory, header.second, progress)
            Protocol.HEADER -> FileReceiver().receiveHeader(connection, directory, header, progress)
            Protocol.DIRECTORY_HEADER -> DirectoryReceiver().receiveHeader(connection, directory, header, progress)
            else -> throw IOException("Ожидается описание файла или каталога")
        }
    }
}
