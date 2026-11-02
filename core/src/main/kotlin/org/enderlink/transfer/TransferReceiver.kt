package org.enderlink.transfer

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import org.enderlink.transfer.resume.ResumeProtocol
import org.enderlink.transfer.resume.ResumableFileReceiver
import java.io.IOException
import java.nio.file.Path

/** Выбирает приём файла или каталога по первому сообщению протокола. */
class TransferReceiver {
    fun receive(connection: Connection, directory: Path, progress: (Long, Long) -> Unit = { _, _ -> }): Path {
        val header = Protocol.read(connection)
        return when (header.first) {
            ResumeProtocol.HEADER -> ResumableFileReceiver().receiveHeader(connection, directory, header.second, progress)
            Protocol.HEADER -> FileReceiver().receiveHeader(connection, directory, header, progress)
            Protocol.DIRECTORY_HEADER -> DirectoryReceiver().receiveHeader(connection, directory, header, progress)
            else -> throw IOException("Ожидается описание файла или каталога")
        }
    }
}
