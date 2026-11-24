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
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

class WorldCommand(
    private val report: (String) -> Unit = ::println,
    private val reportError: (String) -> Unit = { System.err.println(it) },
    private val control: OperationControl = OperationControl(),
) {
    fun run(options: CliOptions) {
        control.checkCancelled()
        val root = Path.of(requireNotNull(options.repository))
        options.source?.let { source ->
            val origin = Path.of(source)
            require(Files.isDirectory(origin, NOFOLLOW_LINKS)) { "Не найден обычный каталог исходного мира: $source" }
            val realSource = origin.toRealPath()
            val realRepository = resolveExistingParent(root)
            require(realSource == realRepository.resolve("world") ||
                !realSource.startsWith(realRepository) && !realRepository.startsWith(realSource)) {
                "Исходный мир и хранилище не должны пересекаться; допустим только рабочий каталог хранилища world."
            }
        }
        if (options.command in setOf("world-publish", "world-checkout", "world-import-owner")) control.protectCommit()
        val repository = WorldRepository(root)
        val progress = TransferProgress("Обработано блоков") { message -> control.checkCancelled(); report(message) }
        when (options.command) {
            "world-versions" -> {
                val active = repository.current()?.versionId
                report("Известные версии (входящие могут быть не докачаны):")
                val versions = repository.versions()
                if (versions.isEmpty()) report("Версий пока нет")
                versions.forEach { report("${if (it.versionId == active) "*" else " "} ${it.versionId} ← ${it.parentVersionId ?: "нет"}") }
            }
            "world-publish" -> {
                report("Подготовка версии закрытого мира...")
                val manifest = repository.publish(Path.of(requireNotNull(options.source)))
                report("Версия опубликована: ${manifest.versionId}")
                report("Рабочий мир: ${repository.workingDirectory}")
                reportPlayerData(repository)
            }
            "world-status" -> {
                val manifest = repository.current()
                if (manifest == null) report("Версия мира пока не опубликована")
                else {
                    report("Мир: ${manifest.worldId}\nВерсия: ${manifest.versionId}\nРодитель: ${manifest.parentVersionId ?: "нет"}\nРабочий мир: ${repository.workingDirectory}")
                    reportPlayerData(repository)
                }
            }
            "world-checkout" -> {
                val id = requireNotNull(options.version)
                repository.checkout(id)
                report("Активирована версия $id: ${repository.workingDirectory}")
            }
            "world-import-owner" -> {
                val owner = UUID.fromString(requireNotNull(options.ownerUuid))
                val manifest = repository.importLegacyPlayer(owner)
                report("Данные владельца $owner добавлены в новую версию ${manifest.versionId}")
                report("Дальше отправляйте эту версию командой world-send --repository ${repository.workingDirectory.parent} без --source исходного одиночного мира")
            }
            "world-send" -> {
                options.source?.let { source ->
                    control.protectCommit()
                    report("Подготовка версии закрытого мира...")
                    val manifest = repository.publish(Path.of(source))
                    report("Версия опубликована: ${manifest.versionId}")
                    reportPlayerData(repository)
                    control.allowCancellation()
                }
                withConnection(options, IrohPurpose.TRANSFER, report = report, control = control) { connection ->
                    val stats = WorldTransfer.send(repository, connection, progress::update, ::reportPhase)
                    report("Версия ${stats.versionId} подтверждена. Передано ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                    report("Сеть → получателю: ${stats.wireSentBytes} байт (данные ${stats.transferredBytes}, метаданные и кадры ${stats.wireSentBytes - stats.transferredBytes}); ← ответы: ${stats.wireReceivedBytes} байт.")
                }
            }
            "world-receive" -> {
                report("Ожидание мира. Хранилище: ${repository.workingDirectory.parent}")
                try {
                    withConnection(options, IrohPurpose.TRANSFER, report = report, control = control) { connection ->
                        val stats = WorldTransfer.receive(repository, connection, progress::update, ::reportPhase)
                        report("Версия ${stats.versionId} активирована: ${repository.workingDirectory}. Получено ${stats.transferredBytes} байт, использовано повторно ${stats.reusedBytes} байт.")
                        report("Сеть ← отправитель: ${stats.wireReceivedBytes} байт; → ответы: ${stats.wireSentBytes} байт.")
                    }
                } catch (conflict: WorldConflictException) {
                    reportError("Входящая версия ${conflict.incoming} сохранена; активная ${conflict.local ?: "отсутствует"} не заменена.")
                    reportError("Если хотите продолжить входящую ветку: world-checkout --repository ${repository.workingDirectory.parent} --version ${conflict.incoming}")
                    throw conflict
                }
            }
        }
    }

    private fun reportPhase(phase: WorldTransferPhase) {
        if (phase == WorldTransferPhase.ACTIVATION) control.protectCommit() else control.checkCancelled()
        val name = when (phase) {
            WorldTransferPhase.MANIFEST -> "проверка описания мира"
            WorldTransferPhase.OBJECTS -> "поиск и передача недостающих блоков"
            WorldTransferPhase.ACTIVATION -> "сборка и активация версии"
            WorldTransferPhase.CONFIRMATION -> "ожидание подтверждения получателя"
        }
        report("Этап: $name")
    }

    private fun reportPlayerData(repository: WorldRepository) {
        val players = PlayerDataInspector.inspect(repository.workingDirectory)
        if (players.legacyPlayerFiles > 0 || players.modernPlayerFiles > 0) {
            report("Файлов игроков: ${players.legacyPlayerFiles} в playerdata, ${players.modernPlayerFiles} в players/data")
        }
        if (players.legacyEmbeddedPlayer) {
            report("Внимание: level.dat содержит данные владельца старого одиночного мира. " +
                "Проверьте его инвентарь при первом запуске сервера.")
        }
        if (players.singleplayerUuid && players.modernPlayerFiles == 0) {
            report("Внимание: найден singleplayer_uuid, но нет файлов в players/data")
        }
        if (!players.levelReadable) report("Внимание: структуру level.dat не удалось проверить")
    }
}

/** Resolve existing parents too, so a new repository beneath a symlink cannot bypass overlap checks. */
private fun resolveExistingParent(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    return if (Files.exists(absolute, NOFOLLOW_LINKS)) absolute.toRealPath()
    else resolveExistingParent(requireNotNull(absolute.parent)).resolve(absolute.fileName)
}
