package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.transfer.resume.ResumableFileSender
import org.enderlink.transfer.resume.ResumableDirectorySender
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SendCommand {
    fun run(host: String, port: Int, file: String?, directory: String? = null) {
        val source = Path.of(requireNotNull(directory ?: file))
        val isDirectory = directory != null
        if (isDirectory && !Files.isDirectory(source, NOFOLLOW_LINKS)) {
            throw IOException("Не найден обычный каталог: $source")
        }
        if (!isDirectory && !Files.isRegularFile(source)) throw IOException("Не найден обычный файл: $source")
        println("Вычисление SHA-256 файлов и блоков…")
        val tree = if (isDirectory) ResumableDirectorySender(source) else null
        val resumable = if (!isDirectory) ResumableFileSender(source) else null
        println("Подключение к $host:$port для отправки ${source.fileName}")
        TcpConnection.connect(Endpoint(host, port)).use { connection ->
            connection.readTimeoutMs = 30_000
            val progress = TransferProgress()
            if (isDirectory) {
                requireNotNull(tree).send(connection, progress::update)
                println("Каталог отправлен. Получатель подтвердил все файлы и структуру.")
            } else {
                requireNotNull(resumable).send(connection, progress::update)
                println("Файл отправлен. Получатель подтвердил размер и SHA-256.")
            }
        }
    }
}
