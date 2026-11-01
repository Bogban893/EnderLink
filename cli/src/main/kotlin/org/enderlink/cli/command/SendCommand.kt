package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.transfer.FileSender
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SendCommand {
    fun run(host: String, port: Int, file: String) {
        val source = Path.of(file)
        if (!Files.isRegularFile(source)) throw IOException("Не найден обычный файл: $source")
        println("Подключение к $host:$port для отправки ${source.fileName}")
        TcpConnection.connect(Endpoint(host, port)).use { connection ->
            connection.readTimeoutMs = 30_000
            val progress = TransferProgress()
            FileSender().send(connection, source, progress::update)
            println("Файл отправлен. Получатель подтвердил размер и SHA-256.")
        }
    }
}
