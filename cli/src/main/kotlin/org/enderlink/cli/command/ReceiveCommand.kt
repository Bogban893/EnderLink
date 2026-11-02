package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.tcp.TcpListener
import org.enderlink.transfer.TransferReceiver
import java.nio.file.Files
import java.nio.file.Path

class ReceiveCommand {
    fun run(host: String?, port: Int, output: String) {
        val directory = Path.of(output)
        Files.createDirectories(directory)
        TcpListener.open(port, host).use { listener ->
            println("Ожидание файла или каталога на ${host ?: "0.0.0.0"}:${listener.port}")
            listener.accept().use { connection ->
                connection.readTimeoutMs = 30_000
                val progress = TransferProgress()
                val saved = TransferReceiver().receive(connection, directory, progress::update)
                println("Сохранено: $saved. SHA-256 файлов проверен.")
            }
        }
    }
}
