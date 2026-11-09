package org.enderlink.cli.command

import org.enderlink.cli.CliOptions
import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransfer
import java.nio.file.Path

class WorldCommand {
    fun run(options: CliOptions) {
        val repository = WorldRepository(Path.of(requireNotNull(options.repository)))
        when (options.command) {
            "world-publish" -> {
                val manifest = repository.publish(Path.of(requireNotNull(options.source)))
                println("Версия опубликована: ${manifest.versionId}")
                println("Рабочий мир: ${repository.workingDirectory}")
            }
            "world-status" -> {
                val manifest = repository.current()
                if (manifest == null) println("Версия мира пока не опубликована")
                else println("Мир: ${manifest.worldId}\nВерсия: ${manifest.versionId}\nРодитель: ${manifest.parentVersionId ?: "нет"}\nРабочий мир: ${repository.workingDirectory}")
            }
            "world-checkout" -> {
                val id = requireNotNull(options.version)
                repository.checkout(id)
                println("Активирована версия $id: ${repository.workingDirectory}")
            }
            "world-send" -> withConnection(options, IrohPurpose.TRANSFER) { connection ->
                val stats = WorldTransfer.send(repository, connection, TransferProgress()::update)
                println("Версия ${stats.versionId} подтверждена. Передано ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                println("Сеть → получателю: ${stats.wireSentBytes} байт (данные ${stats.transferredBytes}, метаданные и кадры ${stats.wireSentBytes - stats.transferredBytes}); ← ответы: ${stats.wireReceivedBytes} байт.")
            }
            "world-receive" -> withConnection(options, IrohPurpose.TRANSFER) { connection ->
                val stats = WorldTransfer.receive(repository, connection, TransferProgress()::update)
                println("Версия ${stats.versionId} активирована: ${repository.workingDirectory}. Получено ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                println("Сеть ← отправитель: ${stats.wireReceivedBytes} байт; → ответы: ${stats.wireSentBytes} байт.")
            }
        }
    }
}
