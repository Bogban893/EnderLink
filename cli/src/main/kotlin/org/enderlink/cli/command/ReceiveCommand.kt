package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.tcp.TcpListener
import org.enderlink.transfer.FileReceiver
import java.nio.file.Files
import java.nio.file.Path

class ReceiveCommand {
    fun run(host: String?, port: Int, output: String) {
        val directory = Path.of(output)
        Files.createDirectories(directory)
        TcpListener.open(port, host).use { listener ->
            println("Ожидание файла на ${host ?: "0.0.0.0"}:${listener.port}")
            listener.accept().use { connection ->
                connection.readTimeoutMs = 30_000
                val progress = TransferProgress()
                val saved = FileReceiver().receive(connection, directory, progress::update)
                println("Файл сохранён: $saved. SHA-256 проверен.")
            }
        }
    }
}
