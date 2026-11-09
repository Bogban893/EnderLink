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
        var operationId: String? = null
        val saved = retryTransfer(options.retries, receiving = true) { attempt ->
            withConnection(options, IrohPurpose.TRANSFER, if (attempt == 0) 0 else 30_000) { connection ->
                val progress = TransferProgress()
                TransferReceiver().receive(connection, directory,
                    expectedOperationId = operationId,
                    onOperation = { id ->
                        if (operationId == null) println("Приём операции $id")
                        operationId = id
                    },
                    progress = progress::update)
            }
        }
        println("Сохранено: $saved. SHA-256 файлов проверен.")
    }
}
