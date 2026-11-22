package org.enderlink.cli.command

import org.enderlink.cli.CliOptions
import org.enderlink.cli.console.TransferProgress
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.world.WorldRepository
import org.enderlink.world.PlayerDataInspector
import org.enderlink.world.WorldConflictException
import org.enderlink.worldtransfer.WorldTransfer
import org.enderlink.worldtransfer.WorldTransferPhase
import java.nio.file.Path
import java.util.UUID

class WorldCommand {
    fun run(options: CliOptions) {
        val repository = WorldRepository(Path.of(requireNotNull(options.repository)))
        when (options.command) {
            "world-publish" -> {
                println("Подготовка версии закрытого мира...")
                val manifest = repository.publish(Path.of(requireNotNull(options.source)))
                println("Версия опубликована: ${manifest.versionId}")
                println("Рабочий мир: ${repository.workingDirectory}")
                reportPlayerData(repository)
            }
            "world-status" -> {
                val manifest = repository.current()
                if (manifest == null) println("Версия мира пока не опубликована")
                else {
                    println("Мир: ${manifest.worldId}\nВерсия: ${manifest.versionId}\nРодитель: ${manifest.parentVersionId ?: "нет"}\nРабочий мир: ${repository.workingDirectory}")
                    reportPlayerData(repository)
                }
            }
            "world-checkout" -> {
                val id = requireNotNull(options.version)
                repository.checkout(id)
                println("Активирована версия $id: ${repository.workingDirectory}")
            }
            "world-import-owner" -> {
                val owner = UUID.fromString(requireNotNull(options.ownerUuid))
                val manifest = repository.importLegacyPlayer(owner)
                println("Данные владельца $owner добавлены в новую версию ${manifest.versionId}")
                println("Дальше отправляйте эту версию командой world-send --repository ${repository.workingDirectory.parent} без --source исходного одиночного мира")
            }
            "world-send" -> {
                options.source?.let { source ->
                    println("Подготовка версии закрытого мира...")
                    val manifest = repository.publish(Path.of(source))
                    println("Версия опубликована: ${manifest.versionId}")
                    reportPlayerData(repository)
                }
                withConnection(options, IrohPurpose.TRANSFER) { connection ->
                    val stats = WorldTransfer.send(repository, connection, TransferProgress("Обработано блоков")::update, ::reportPhase)
                    println("Версия ${stats.versionId} подтверждена. Передано ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                    println("Сеть → получателю: ${stats.wireSentBytes} байт (данные ${stats.transferredBytes}, метаданные и кадры ${stats.wireSentBytes - stats.transferredBytes}); ← ответы: ${stats.wireReceivedBytes} байт.")
                }
            }
            "world-receive" -> {
                println("Ожидание мира. Хранилище: ${repository.workingDirectory.parent}")
                try {
                    withConnection(options, IrohPurpose.TRANSFER) { connection ->
                        val stats = WorldTransfer.receive(repository, connection, TransferProgress("Обработано блоков")::update, ::reportPhase)
                        println("Версия ${stats.versionId} активирована: ${repository.workingDirectory}. Получено ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                        println("Сеть ← отправитель: ${stats.wireReceivedBytes} байт; → ответы: ${stats.wireSentBytes} байт.")
                    }
                } catch (conflict: WorldConflictException) {
                    System.err.println("Входящая версия ${conflict.incoming} сохранена; активная ${conflict.local ?: "отсутствует"} не заменена.")
                    System.err.println("Если хотите продолжить входящую ветку: world-checkout --repository ${repository.workingDirectory.parent} --version ${conflict.incoming}")
                    throw conflict
                }
            }
        }
    }

    private fun reportPhase(phase: WorldTransferPhase) {
        val name = when (phase) {
            WorldTransferPhase.MANIFEST -> "проверка описания мира"
            WorldTransferPhase.OBJECTS -> "поиск и передача недостающих блоков"
            WorldTransferPhase.ACTIVATION -> "сборка и активация версии"
            WorldTransferPhase.CONFIRMATION -> "ожидание подтверждения получателя"
        }
        println("Этап: $name")
    }

    private fun reportPlayerData(repository: WorldRepository) {
        val players = PlayerDataInspector.inspect(repository.workingDirectory)
        if (players.legacyPlayerFiles > 0 || players.modernPlayerFiles > 0) {
            println("Файлов игроков: ${players.legacyPlayerFiles} в playerdata, ${players.modernPlayerFiles} в players/data")
        }
        if (players.legacyEmbeddedPlayer) {
            println("Внимание: level.dat содержит данные владельца старого одиночного мира. " +
                "Проверьте его инвентарь при первом запуске сервера.")
        }
        if (players.singleplayerUuid && players.modernPlayerFiles == 0) {
            println("Внимание: найден singleplayer_uuid, но нет файлов в players/data")
        }
        if (!players.levelReadable) println("Внимание: структуру level.dat не удалось проверить")
    }
}
