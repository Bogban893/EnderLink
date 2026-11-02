package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.transfer.FileSender
import org.enderlink.transfer.DirectorySender
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
        println("Подключение к $host:$port для отправки ${source.fileName}")
        TcpConnection.connect(Endpoint(host, port)).use { connection ->
            connection.readTimeoutMs = 30_000
            val progress = TransferProgress()
            if (isDirectory) {
                DirectorySender().send(connection, source, progress::update)
                println("Каталог отправлен. Получатель подтвердил все файлы и структуру.")
            } else {
                FileSender().send(connection, source, progress::update)
                println("Файл отправлен. Получатель подтвердил размер и SHA-256.")
            }
        }
    }
}
