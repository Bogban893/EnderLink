package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.transfer.DesktopOperations
import org.enderlink.desktop.minecraft.MinecraftSaves
import org.enderlink.desktop.transfer.Route
import org.enderlink.desktop.transfer.TransferTarget
import org.enderlink.desktop.settings.DesktopPreferences
import org.enderlink.desktop.settings.DesktopSettings
import org.enderlink.desktop.ui.DesktopFrame
import org.enderlink.desktop.ui.dialogs.DesktopDialogs
import org.enderlink.desktop.ui.shortId
import org.enderlink.desktop.ui.size
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.world.PlayerDataInspector
import org.enderlink.world.WorldConflictException
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.ParentFingerprint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JRadioButton
import javax.swing.SwingUtilities

/** Coordinates desktop actions; screens only collect input and display results. */
internal class DesktopController(
    private val view: DesktopFrame,
    private val settings: DesktopSettings = DesktopSettings(),
    private val operations: DesktopOperations = DesktopOperations(),
) {
    private val dialogs = DesktopDialogs(view)
    @Volatile private var installingMinecraft = false
    private var lastParentFingerprint: ParentFingerprint? = null
    private val progress = TransferProgressPresenter(view.sendScreen, view.receiveScreen)
    private val jobs = DesktopJobs(
        setBusy = view::setBusy,
        status = view::status,
        beforeStart = { cancellable -> if (cancellable) operations.begin() },
        afterFinish = { installingMinecraft = false },
        onFailure = ::handleFailure,
    )
    val servers = ServerController(view, jobs, dialogs, ::repository, ::saveSettings, ::refreshStatus)

    fun start() {
        val preferences = settings.load()
        view.repositoryField.text = preferences.repository
        view.receiveScreen.savesField.text = preferences.saves
        view.receiveScreen.saveNameField.text = preferences.saveName
        view.serverScreen.serverBaseField.text = preferences.serverBase
        view.serverScreen.jarField.text = preferences.jar
        lastParentFingerprint = preferences.parentFingerprint
        refreshStatus()
        refreshFriends()
    }

    fun closeWindow() {
        if (jobs.isBusy) { dialogs.warning(messages.text("status.closeBusy")); return }
        servers.closeWindow()
    }

    private fun handleFailure(failure: Throwable, cancellable: Boolean) {
        if (failure is InterruptedException || cancellable && operations.wasCancelled) view.status(messages.text("status.cancelled"))
        else handleError(failure)
        servers.operationFailed()
    }

    fun startSend() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopAndPublishFirst")); return }
        val source = view.sendScreen.sourceField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning(messages.text("transfer.source.invalid")); return }
        }
        if (source != null && (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("level.dat")))) {
            dialogs.warning(messages.text("transfer.source.required")); return
        }
        val target = target(view.sendScreen.sendRoute, view.sendScreen.friendSend, view.sendScreen.sendHost, view.sendScreen.sendPort) ?: return
        val repository = repository() ?: return
        if (source != null) {
            val origin = source.toAbsolutePath().normalize()
            val repositoryRoot = repository.workingDirectory.parent
            if (origin != repository.workingDirectory &&
                (origin.startsWith(repositoryRoot) || repositoryRoot.startsWith(origin))) {
                dialogs.warning(messages.text("repository.sourceOverlap"))
                return
            }
        }
        saveSettings()
        progress.reset()
        progress.receiving = false
        jobs.run(messages.text("transfer.sending"), cancellable = true, {
            operations.send(repository, source, target, ::statusAsync, progress::update)
        }) { result ->
            view.status("Мир передан и подтверждён · ${shortId(result.versionId)}")
            view.sendScreen.sendProgressText.text = "Готово · передано ${size(result.transferredBytes)}, повторно использовано ${size(result.reusedBytes)}"
            refreshStatus()
        }
    }

    fun startReceive() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val target = target(view.receiveScreen.receiveRoute, view.receiveScreen.friendReceive, null, view.receiveScreen.receivePort) ?: return
        val repository = repository() ?: return
        val saves = minecraftDestination() ?: return
        saveSettings()
        progress.reset()
        progress.receiving = true
        jobs.run(messages.text("transfer.receiving"), cancellable = true, {
            if (repository.current() == null) {
                statusAsync(messages.text("transfer.comparing"))
                val cached = MinecraftSaves.cacheExisting(repository, saves.first, saves.second)
                if (cached > 0) statusAsync("Повторно используем $cached блоков существующего мира")
            }
            if (operations.wasCancelled) throw InterruptedException(messages.text("status.cancelled"))
            val result = operations.receive(repository, target, ::statusAsync, progress::update)
            SwingUtilities.invokeLater { lastParentFingerprint = result.parentFingerprint; saveSettings() }
            if (operations.wasCancelled) throw InterruptedException(messages.text("status.cancelled"))
            installingMinecraft = true
            SwingUtilities.invokeLater { view.setCanCancel(false) }
            statusAsync(messages.text("transfer.installing"))
            val installed = try { MinecraftSaves.install(repository, saves.first, saves.second, result.parentFingerprint) }
            catch (error: Exception) {
                throw IOException("Мир получен в EnderLink, но не добавлен в Minecraft: ${error.message}. " +
                    "Закройте мир и нажмите «Обновить Minecraft».", error)
            }
            result to installed
        }) { (result, installed) ->
            view.status("Мир принят · ${shortId(result.versionId)}")
            view.receiveScreen.receiveProgressText.text = "Готово · получено ${size(result.transferredBytes)}, в Minecraft: ${installed.directory.fileName}"
            refreshStatus()
        }
    }

    private fun minecraftDestination(): Pair<Path, String>? {
        val path = view.receiveScreen.savesField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning(messages.text("minecraft.saves.invalid")); return null }
        } ?: run { dialogs.warning(messages.text("minecraft.saves.required")); return null }
        val name = view.receiveScreen.saveNameField.text.trim()
        if (name.isBlank()) { dialogs.warning(messages.text("minecraft.folder.required")); return null }
        return path to name
    }

    fun installMinecraft() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val repository = repository() ?: return
        val destination = minecraftDestination() ?: return
        saveSettings()
        jobs.run(messages.text("minecraft.installing"), {
            MinecraftSaves.install(repository, destination.first, destination.second, lastParentFingerprint)
        }) { installed ->
            view.status("Мир Minecraft обновлён · ${installed.directory}")
            view.receiveScreen.receiveProgressText.text = "В Minecraft: ${installed.directory} · изменено ${installed.replacedFiles} файлов"
        }
    }

    private fun target(group: Pair<JRadioButton, JRadioButton>, friend: javax.swing.JComboBox<String>,
                       host: javax.swing.JTextField?, port: javax.swing.JTextField): TransferTarget? {
        if (group.first.isSelected) {
            val name = (friend.selectedItem as? String).orEmpty()
            if (name.isBlank()) { dialogs.warning(messages.text("transfer.friend.addFirst")); return null }
            return TransferTarget(Route.FRIEND, friend = name)
        }
        val number = port.text.trim().toIntOrNull()
        if (number == null || number !in 1..65535) { dialogs.warning(messages.text("transfer.tcpPort.invalid")); return null }
        val address = host?.text?.trim().orEmpty()
        if (host != null && address.isBlank()) { dialogs.warning(messages.text("transfer.recipientAddress.required")); return null }
        return TransferTarget(Route.LOCAL, host = address, port = number)
    }

    fun addFriend() {
        val name = view.friendsScreen.friendName.text.trim(); val id = view.friendsScreen.friendId.text.trim()
        jobs.run(messages.text("friends.saving"), { IrohProfile.open().use { it.add(name, id) } }) {
            view.friendsScreen.friendName.text = ""; view.friendsScreen.friendId.text = ""
            view.status("Друг $name добавлен")
            refreshFriends()
        }
    }

    fun importOwner() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val owner = runCatching { UUID.fromString(view.homeScreen.ownerField.text.trim()) }.getOrNull()
        if (owner == null || owner.toString() != view.homeScreen.ownerField.text.trim().lowercase()) {
            dialogs.warning(messages.text("player.uuid.required"))
            return
        }
        val repository = repository() ?: return
        jobs.run(messages.text("player.importing"), { repository.importLegacyPlayer(owner) }) { manifest ->
            view.status("Вещи владельца перенесены · ${shortId(manifest.versionId)}")
            refreshStatus()
        }
    }

    fun refreshFriends() {
        jobs.run(messages.text("friends.profileLoading"), { IrohProfile.open().use { it.id to it.friends() } }) { (id, friends) ->
            view.friendsScreen.ownId.text = id
            val previousSend = view.sendScreen.friendSend.selectedItem as? String
            val previousReceive = view.receiveScreen.friendReceive.selectedItem as? String
            view.sendScreen.friendSend.removeAllItems(); view.receiveScreen.friendReceive.removeAllItems()
            friends.keys.forEach { view.sendScreen.friendSend.addItem(it); view.receiveScreen.friendReceive.addItem(it) }
            if (previousSend in friends) view.sendScreen.friendSend.selectedItem = previousSend
            if (previousReceive in friends) view.receiveScreen.friendReceive.selectedItem = previousReceive
            view.friendsScreen.friendsList.text = if (friends.isEmpty()) messages.text("friends.empty") else
                "<html>" + friends.keys.joinToString("<br>") { "<span style='color:#84be60'>◆</span>  $it" } + "</html>"
            view.status("Профиль готов · друзей: ${friends.size}")
        }
    }

    fun refreshStatus() {
        val path = view.repositoryField.text.trim()
        if (path.isBlank()) {
            view.homeScreen.statusWorld.text = messages.text("repository.unselected"); view.homeScreen.statusDetail.text = messages.text("repository.selectHint"); view.homeScreen.playerHint.text = ""
            return
        }
        jobs.run(messages.text("repository.reading"), {
            val repo = WorldRepository(Path.of(path))
            val current = repo.current()
            val player = if (current == null) null else PlayerDataInspector.inspect(repo.workingDirectory)
            Triple(repo, current, player)
        }) { (repo, current, player) ->
            view.homeScreen.statusWorld.text = if (current == null) messages.text("repository.empty") else "Версия ${shortId(current.versionId)}"
            view.homeScreen.statusDetail.text = if (current == null) messages.text("repository.sourceHint") else
                "Рабочая копия: ${repo.workingDirectory}"
            view.homeScreen.playerHint.text = when {
                player == null -> ""
                player.legacyEmbeddedPlayer -> messages.text("player.legacyHint")
                player.singleplayerUuid && player.modernPlayerFiles == 0 -> messages.text("player.modernMissingHint")
                else -> "Файлов игроков: ${player.legacyPlayerFiles + player.modernPlayerFiles}"
            }
            view.status(messages.text("repository.updated"))
            if (view.friendsScreen.ownId.text.isBlank()) refreshFriends()
        }
    }

    fun openWorldFolder() {
        val path = view.repositoryField.text.trim().takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() }?.resolve("world")
        if (path == null || !Files.isDirectory(path)) { dialogs.warning(messages.text("repository.workMissing")); return }
        runCatching { java.awt.Desktop.getDesktop().open(path.toFile()) }
            .onFailure { dialogs.warning("Не удалось открыть папку: ${it.message}") }
    }

    private fun repository(): WorldRepository? {
        val path = view.repositoryField.text.trim()
        if (path.isBlank()) { dialogs.warning(messages.text("repository.required")); return null }
        return runCatching { WorldRepository(Path.of(path)) }.getOrElse { dialogs.warning(it.message ?: messages.text("repository.openFailed")); null }
    }

    private fun handleError(failure: Throwable) {
        if (failure is WorldConflictException) {
            view.status(messages.text("conflict.saved"))
            if (dialogs.confirmIncomingVersion(failure.local, failure.incoming)) {
                val repository = repository() ?: return
                jobs.run(messages.text("conflict.activating"), { repository.checkout(failure.incoming) }) {
                    view.status(messages.text("conflict.activated"))
                    refreshStatus()
                }
            }
            return
        }
        val message = failure.message ?: failure.javaClass.simpleName
        view.status("Ошибка: $message")
        dialogs.error(message)
    }

    fun cancelOperation() {
        if (!jobs.isBusy) return
        if (installingMinecraft) { view.status(messages.text("minecraft.installFinishing")); return }
        view.status(messages.text("status.cancelling"))
        Thread({ operations.cancel() }, "enderlink-cancel").apply { isDaemon = true; start() }
    }

    private fun statusAsync(text: String) = SwingUtilities.invokeLater { view.status(text) }

    fun saveSettings() {
        settings.save(DesktopPreferences(
            repository = view.repositoryField.text,
            saves = view.receiveScreen.savesField.text,
            saveName = view.receiveScreen.saveNameField.text,
            serverBase = view.serverScreen.serverBaseField.text,
            jar = view.serverScreen.jarField.text,
            parentFingerprint = lastParentFingerprint,
        ))
    }

    fun chooseDirectory(target: javax.swing.JTextField) {
        dialogs.chooseDirectory(target.text)?.let {
            target.text = it
            saveSettings()
            if (target === view.repositoryField) refreshStatus()
        }
    }

    fun chooseFile(target: javax.swing.JTextField) {
        dialogs.chooseJar()?.let { target.text = it; saveSettings() }
    }
}
