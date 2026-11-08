package org.enderlink.cli.command

import org.enderlink.cli.console.TransferProgress
import org.enderlink.cli.CliOptions
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.transfer.TransferReceiver
import java.nio.file.Files
import java.nio.file.Path

class ReceiveCommand {
    fun run(options: CliOptions) {
        val directory = Path.of(requireNotNull(options.output))
        Files.createDirectories(directory)
        withConnection(options, IrohPurpose.TRANSFER) { connection ->
            val progress = TransferProgress()
            val saved = TransferReceiver().receive(connection, directory, progress::update)
            println("Сохранено: $saved. SHA-256 файлов проверен.")
        }
    }
}
