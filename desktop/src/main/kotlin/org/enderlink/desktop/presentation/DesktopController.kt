package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.transfer.DesktopOperations
import org.enderlink.desktop.transfer.WorldPublicationException
import org.enderlink.desktop.minecraft.MinecraftSaves
import org.enderlink.desktop.minecraft.ManagedSaveState
import org.enderlink.desktop.transfer.Route
import org.enderlink.desktop.transfer.TransferTarget
import org.enderlink.desktop.settings.DesktopPreferences
import org.enderlink.desktop.settings.DesktopSettings
import org.enderlink.desktop.settings.DesktopWorldLibrary
import org.enderlink.desktop.settings.WorldUiState
import org.enderlink.desktop.ui.DesktopFrame
import org.enderlink.desktop.ui.dialogs.DesktopDialogs
import org.enderlink.desktop.ui.shortId
import org.enderlink.desktop.ui.size
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.RemoteTransferException
import org.enderlink.network.TransferFailureClassifier
import org.enderlink.network.TransferFailureKind
import org.enderlink.network.TransferDiagnostics
import org.enderlink.network.TransferRejectedException
import org.enderlink.network.discovery.DiscoveryClient
import org.enderlink.world.PlayerDataInspector
import org.enderlink.world.PlayerProfileChoice
import org.enderlink.world.PlayerProfileConflictException
import org.enderlink.world.WorldConflictException
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldEntryType
import org.enderlink.world.WorldManifest
import org.enderlink.worldtransfer.ParentFingerprint
import org.enderlink.worldtransfer.WorldTransferPhase
import org.enderlink.worldtransfer.WorldTransferTimeoutException
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val worldStates = linkedMapOf<String, WorldUiState>()
    private var activeRepositoryPath = ""
    private var ownerUuid = ""
    private val knownRepositories = linkedSetOf<String>()
    private val chooserDirectories = linkedMapOf<String, String>()
    private var displayedRepositories = emptyList<String>()
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
        knownRepositories.addAll(preferences.knownRepositories)
        chooserDirectories.putAll(preferences.chooserDirectories)
        worldStates.putAll(preferences.worldStates)
        activeRepositoryPath = repositoryKey(preferences.repository)
        if (activeRepositoryPath.isNotBlank() && activeRepositoryPath !in worldStates) {
            worldStates[activeRepositoryPath] = WorldUiState(preferences.saveName, preferences.parentFingerprint)
        }
        view.repositoryField.text = preferences.repository
        view.friendsScreen.coordinatorUrl.text = preferences.coordinatorUrl
        operations.coordinatorUrl = preferences.coordinatorUrl
        view.homeScreen.libraryRootField.text = preferences.libraryRoot
        view.receiveScreen.savesField.text = preferences.saves
        view.receiveScreen.saveNameField.text = preferences.saveName
        ownerUuid = preferences.ownerUuid
        view.receiveScreen.ownerField.document = view.sendScreen.ownerField.document
        view.homeScreen.ownerField.document = view.sendScreen.ownerField.document
        view.sendScreen.ownerField.text = ownerUuid
        view.receiveScreen.lanNameField.document = view.sendScreen.lanNameField.document
        view.homeScreen.lanNameField.document = view.sendScreen.lanNameField.document
        view.sendScreen.lanNameField.text = preferences.lanPlayerName
        view.serverScreen.serverBaseField.text = preferences.serverBase
        view.serverScreen.jarField.text = preferences.jar
        lastParentFingerprint = worldStates[activeRepositoryPath]?.parentFingerprint ?: preferences.parentFingerprint
        jobs.run(messages.text("repository.reading"), {
            val library = DesktopWorldLibrary(Path.of(preferences.libraryRoot))
            library.prepare(Path.of(preferences.saves))
            val existing = preferences.repository.takeIf(String::isNotBlank)?.let(Path::of)
                ?: library.repositories().firstOrNull()
            (existing ?: library.create()) to if (existing == null) availableSaveName(Path.of(preferences.saves), preferences.saveName,
                preferences.worldStates.values.map(WorldUiState::saveName).toSet())
                else preferences.saveName
        }) { (selected, saveName) ->
            activateRepository(selected.toString())
            if (repositoryKey(selected.toString()) !in worldStates) view.receiveScreen.saveNameField.text = saveName
            saveSettings()
            refreshStatus()
        }
    }

    fun closeWindow() {
        if (jobs.isBusy) { dialogs.warning(messages.text("status.closeBusy")); return }
        servers.closeWindow()
    }

    private fun handleFailure(failure: Throwable, cancellable: Boolean) {
        if (failure is InterruptedException || cancellable && operations.wasCancelled) {
            progress.fail(cancelled = true)
            view.statusCancelled(messages.text("status.cancelled"))
        } else if (failure is TransferRejectedException) {
            progress.fail(cancelled = true)
            view.statusCancelled(messages.text("transfer.declined"))
        } else {
            if (failure is WorldConflictException) progress.conflict() else progress.fail()
            handleError(failure)
        }
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
        val identity = if (source != null || view.sendScreen.lanNameField.text.isNotBlank())
            selectedPlayer(view.sendScreen.ownerField) ?: return else null
        val owner = identity?.first
        val guest = identity?.second
        if (owner != null) ownerUuid = owner.toString()
        if (source != null) {
            val origin = source.toAbsolutePath().normalize()
            val repositoryRoot = repository.workingDirectory.parent
            if (origin != repository.workingDirectory &&
                (origin.startsWith(repositoryRoot) || repositoryRoot.startsWith(origin))) {
                dialogs.warning(messages.text("repository.sourceOverlap"))
                return
            }
        }
        val savesPath = view.receiveScreen.savesField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning(messages.text("minecraft.saves.invalid")); return }
        }
        saveSettings()
        progress.receiving = false
        progress.reset()
        val transferId = UUID.randomUUID()
        jobs.run(messages.text("transfer.sending"), cancellable = true, {
            if (repository.current() != null && savesPath != null &&
                (source == null || source.toAbsolutePath().normalize().startsWith(savesPath.toAbsolutePath().normalize()))) {
                val installed = MinecraftSaves.inspectManaged(repository, savesPath)
                if (source != null) {
                    installed.firstOrNull { it.directory == source.toAbsolutePath().normalize() && it.state == ManagedSaveState.OPEN }?.let {
                        throw IOException(messages.text("send.installedOpen", it.directory))
                    }
                } else {
                    installed.firstOrNull { it.state == ManagedSaveState.OPEN }?.let {
                        throw IOException(messages.text("send.installedOpen", it.directory))
                    }
                    installed.firstOrNull { it.state == ManagedSaveState.CHANGED }?.let {
                        throw IOException(messages.text("send.installedChanged", it.directory))
                    }
                }
            }
            try { operations.send(repository, source, target, ::statusAsync, progress::update, owner, guest) }
            catch (error: WorldPublicationException) { throw error }
            catch (error: IOException) { throw TransferOperationException(transferId, error) }
        }) { result ->
            progress.complete()
            view.sendScreen.sendProgressText.text = messages.text("transfer.sentSummary", size(result.transferredBytes), size(result.reusedBytes))
            refreshStatus()
            view.statusSuccess(messages.text("transfer.sent", shortId(result.versionId)))
        }
    }

    fun sendCurrent() {
        view.sendScreen.sourceField.text = ""
        view.showSend()
    }

    fun selectLibraryWorld(index: Int) {
        val path = displayedRepositories.getOrNull(index) ?: return
        activateRepository(path)
        saveSettings()
        refreshStatus()
    }

    fun newWorld() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val saves = view.receiveScreen.savesField.text.trim().takeIf(String::isNotBlank)
            ?: run { dialogs.warning(messages.text("minecraft.saves.required")); return }
        val root = view.homeScreen.libraryRootField.text
        val preferredName = view.receiveScreen.saveNameField.text
        val reservedNames = worldStates.values.map(WorldUiState::saveName).toSet() + preferredName
        jobs.run(messages.text("library.creating"), {
            val created = DesktopWorldLibrary(Path.of(root)).apply { prepare(Path.of(saves)) }.create()
            created to availableSaveName(Path.of(saves), preferredName, reservedNames)
        }) { (created, saveName) ->
            activateRepository(created.toString())
            view.receiveScreen.saveNameField.text = saveName
            knownRepositories.add(created.toString())
            saveSettings()
            refreshStatus()
            view.showReceive()
        }
    }

    fun chooseLibraryRoot() {
        val selected = selectDirectory(DirectoryPurpose.LIBRARY, view.homeScreen.libraryRootField.text) ?: return
        saveSettings()
        val previous = view.homeScreen.libraryRootField.text
        if (Path.of(selected).toAbsolutePath().normalize() == Path.of(previous).toAbsolutePath().normalize()) return
        if (!dialogs.confirmLibraryMove(previous, selected)) return
        val saves = view.receiveScreen.savesField.text.trim().takeIf(String::isNotBlank)
            ?: run { dialogs.warning(messages.text("minecraft.saves.required")); return }
        val current = view.repositoryField.text.trim()
        jobs.run(messages.text("library.changing"), {
            val library = DesktopWorldLibrary(Path.of(selected))
            val moved = DesktopWorldLibrary(Path.of(previous)).copyTo(library, Path.of(saves))
            moved to (moved[Path.of(current).toAbsolutePath().normalize()]
                ?: current.takeIf(String::isNotBlank)?.let(Path::of)
                ?: library.repositories().firstOrNull() ?: library.create())
        }) { (moved, repository) ->
            rememberWorldState()
            moved.forEach { (old, new) ->
                worldStates.remove(repositoryKey(old.toString()))?.let { worldStates[repositoryKey(new.toString())] = it }
            }
            knownRepositories.removeIf { known -> runCatching { Path.of(known).toAbsolutePath().normalize() in moved.keys }.getOrDefault(false) }
            knownRepositories.addAll(moved.values.map(Path::toString))
            view.homeScreen.libraryRootField.text = selected
            activateRepository(repository.toString(), rememberCurrent = false)
            knownRepositories.add(repository.toString())
            saveSettings()
            refreshStatus()
        }
    }

    fun importWorld() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val source = selectDirectory(DirectoryPurpose.SOURCE,
            chooserDirectories[DirectoryPurpose.SOURCE.key] ?: view.receiveScreen.savesField.text) ?: return
        saveSettings()
        val sourcePath = runCatching { Path.of(source) }.getOrElse { dialogs.warning(messages.text("transfer.source.invalid")); return }
        if (!Files.isRegularFile(sourcePath.resolve("level.dat"), NOFOLLOW_LINKS)) {
            dialogs.warning(messages.text("transfer.source.required")); return
        }
        val (owner, guest) = selectedPlayer(view.homeScreen.ownerField) ?: return
        ownerUuid = owner.toString()
        val saves = view.receiveScreen.savesField.text.trim().takeIf(String::isNotBlank)
            ?: run { dialogs.warning(messages.text("minecraft.saves.required")); return }
        val root = view.homeScreen.libraryRootField.text
        jobs.run(messages.text("library.importing"), {
            val library = DesktopWorldLibrary(Path.of(root))
            library.prepare(Path.of(saves))
            val repositoryPath = library.create()
            val repository = WorldRepository(repositoryPath)
            MinecraftSaves.requireClosed(sourcePath)
            repository.publishSingleplayer(sourcePath, owner, guest)
            runCatching { library.name(repositoryPath, sourcePath.fileName.toString()) }
                .onFailure { System.err.println("EnderLink library name: ${it.message}") }
            repositoryPath
        }) { imported ->
            activateRepository(imported.toString())
            view.sendScreen.sourceField.text = ""
            knownRepositories.add(imported.toString())
            saveSettings()
            refreshStatus()
            view.showSend()
        }
    }

    fun selectInstalledForSend() {
        val repository = repository() ?: return
        val saves = view.receiveScreen.savesField.text.trim().takeIf(String::isNotEmpty)?.let {
            runCatching { Path.of(it) }.getOrElse { dialogs.warning(messages.text("minecraft.saves.invalid")); return }
        } ?: run { dialogs.warning(messages.text("minecraft.saves.required")); return }
        val preferred = view.receiveScreen.saveNameField.text.trim()
        jobs.run(messages.text("repository.reading"), { MinecraftSaves.inspectManaged(repository, saves) }) { installed ->
            val selected = installed.singleOrNull() ?: installed.singleOrNull { it.directory.fileName.toString() == preferred }
            when {
                selected == null -> dialogs.warning(messages.text(if (installed.isEmpty()) "send.installedMissing" else "send.installedMultiple"))
                selected.state == ManagedSaveState.OPEN -> dialogs.warning(messages.text("send.installedOpen", selected.directory))
                selected.state == ManagedSaveState.CHANGED && !Files.isDirectory(selected.directory) ->
                    dialogs.warning(messages.text("send.installedMissing"))
                else -> {
                    view.sendScreen.sourceField.text = selected.directory.toString()
                    view.sendScreen.ownerField.text = ownerUuid
                    view.status(messages.text("send.installedSelected", selected.directory))
                }
            }
        }
    }

    fun startReceive() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val target = target(view.receiveScreen.receiveRoute, view.receiveScreen.friendReceive, null, view.receiveScreen.receivePort) ?: return
        val repository = repository() ?: return
        val saves = minecraftDestination() ?: return
        val libraryRoot = Path.of(view.homeScreen.libraryRootField.text)
        val (owner, guest) = selectedPlayer(view.receiveScreen.ownerField) ?: return
        ownerUuid = owner.toString()
        saveSettings()
        progress.receiving = true
        progress.reset()
        val transferId = UUID.randomUUID()
        jobs.run(messages.text("transfer.receiving"), cancellable = true, {
            if (repository.current() == null) {
                statusAsync(messages.text("transfer.comparing"))
                val cached = MinecraftSaves.cacheExisting(repository, saves.first, saves.second)
                if (cached > 0) statusAsync(messages.text("transfer.cached", cached.toString()))
            }
            if (operations.wasCancelled) throw InterruptedException(messages.text("status.cancelled"))
            val result = try { operations.receive(repository, target, ::statusAsync, progress::update) { manifest, peerId ->
                confirmIncomingWorld(target, peerId, manifest)
            } }
                catch (error: WorldConflictException) { throw error }
                catch (error: TransferRejectedException) { throw error }
                catch (error: IOException) { throw TransferOperationException(transferId, error) }
            val library = DesktopWorldLibrary(libraryRoot)
            if (repository.workingDirectory.parent.parent == library.directory) {
                runCatching { library.name(repository.workingDirectory.parent, saves.second) }
                    .onFailure { System.err.println("EnderLink library name: ${it.message}") }
            }
            SwingUtilities.invokeLater { lastParentFingerprint = result.parentFingerprint; saveSettings() }
            if (operations.wasCancelled) throw InterruptedException(messages.text("status.cancelled"))
            installingMinecraft = true
            SwingUtilities.invokeLater { view.setCanCancel(false) }
            statusAsync(messages.text("transfer.installing"))
            val installed = try {
                MinecraftSaves.install(repository, saves.first, saves.second, result.parentFingerprint, owner, guest, ::resolvePlayerProfileConflict)
            }
            catch (error: InterruptedException) { throw error }
            catch (error: Exception) {
                throw IOException(messages.text("minecraft.receivedInstallFailed", error.message), error)
            }
            result to installed
        }) { (result, installed) ->
            progress.complete()
            view.receiveScreen.receiveProgressText.text = messages.text("transfer.receivedSummary", size(result.transferredBytes), installed.directory.fileName)
            refreshStatus()
            view.statusSuccess(messages.text("transfer.received", shortId(result.versionId)))
        }
    }

    private fun confirmIncomingWorld(target: TransferTarget, peerId: String?, manifest: WorldManifest): Boolean {
        val peer = if (target.route == Route.FRIEND) messages.text("transfer.peerFriend", target.friend, requireNotNull(peerId))
            else messages.text("transfer.peerTcp")
        val files = manifest.entries.count { it.type == WorldEntryType.FILE }
        val bytes = manifest.entries.fold(0L) { total, entry -> Math.addExact(total, entry.size) }
        val accepted = AtomicBoolean(false)
        SwingUtilities.invokeAndWait {
            if (!operations.wasCancelled) {
                accepted.set(dialogs.confirmIncomingWorld(peer, manifest.worldId, manifest.versionId, files, size(bytes)))
            }
        }
        return accepted.get() && !operations.wasCancelled
    }

    private fun resolvePlayerProfileConflict(conflict: PlayerProfileConflictException): PlayerProfileChoice {
        val choice = AtomicReference<PlayerProfileChoice?>()
        SwingUtilities.invokeAndWait { choice.set(dialogs.choosePlayerProfile(conflict)) }
        return choice.get() ?: throw InterruptedException(messages.text("status.cancelled"))
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
        val (owner, guest) = selectedPlayer(view.receiveScreen.ownerField) ?: return
        ownerUuid = owner.toString()
        saveSettings()
        jobs.run(messages.text("minecraft.installing"), {
            MinecraftSaves.install(repository, destination.first, destination.second, lastParentFingerprint, owner, guest, ::resolvePlayerProfileConflict)
        }) { installed ->
            view.status(messages.text("minecraft.updated", installed.directory))
            view.receiveScreen.receiveProgressText.text = messages.text("minecraft.updatedSummary", installed.directory, installed.replacedFiles.toString())
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
            view.status(messages.text("friends.added", name))
            refreshFriends()
        }
    }

    fun saveCoordinator() {
        val address = view.friendsScreen.coordinatorUrl.text.trim()
        if (address.isNotEmpty()) try { DiscoveryClient(URI.create(address)) }
        catch (error: Exception) {
            dialogs.warning(messages.text("discovery.invalidAddress", error.message))
            return
        }
        operations.coordinatorUrl = address
        saveSettings()
        view.status(messages.text("discovery.saved"))
    }

    fun importOwner() {
        if (servers.isRunning) { dialogs.warning(messages.text("server.stopFirst")); return }
        val owner = runCatching { UUID.fromString(view.homeScreen.ownerField.text.trim()) }.getOrNull()
        if (owner == null || owner.toString() != view.homeScreen.ownerField.text.trim().lowercase()) {
            dialogs.warning(messages.text("player.uuid.required"))
            return
        }
        val repository = repository() ?: return
        ownerUuid = owner.toString()
        jobs.run(messages.text("player.importing"), { repository.importLegacyPlayer(owner) }) { manifest ->
            view.status(messages.text("player.imported", shortId(manifest.versionId)))
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
            view.status(messages.text("friends.ready", friends.size.toString()))
        }
    }

    fun refreshStatus() {
        val path = view.repositoryField.text.trim()
        if (repositoryKey(path) != activeRepositoryPath) activateRepository(path)
        if (path.isBlank()) {
            view.homeScreen.statusWorld.text = messages.text("repository.unselected"); view.homeScreen.statusDetail.text = messages.text("repository.selectHint"); view.homeScreen.playerHint.text = ""
            view.homeScreen.sendCurrentButton.isVisible = false
            return
        }
        val libraryPaths = knownRepositories.toList()
        val libraryRoot = view.homeScreen.libraryRootField.text.trim()
        jobs.run(messages.text("repository.reading"), {
            val repo = WorldRepository(Path.of(path))
            val current = repo.current()
            val player = if (current == null) null else PlayerDataInspector.inspect(repo.workingDirectory)
            val managed = DesktopWorldLibrary(Path.of(libraryRoot))
            val library = (managed.repositories().map(Path::toString) + libraryPaths + path).distinct().mapNotNull { candidate ->
                runCatching {
                    val directory = Path.of(candidate)
                    if (!Files.isDirectory(directory.resolve(".enderlink"), NOFOLLOW_LINKS)) null
                    else {
                        val manifest = if (candidate == path) current else WorldRepository(directory).current()
                        LibraryWorld(candidate, managed.name(directory), manifest?.worldId, manifest?.versionId)
                    }
                }.getOrNull()
            }
            (Triple(repo, current, player) to library)
        }) { (overview, library) ->
            val (repo, current, player) = overview
            view.homeScreen.statusWorld.text = if (current == null) messages.text("repository.empty") else
                messages.text("repository.worldVersion", shortId(current.worldId), shortId(current.versionId))
            view.homeScreen.sendCurrentButton.isVisible = current != null
            displayedRepositories = library.map { it.path }
            view.homeScreen.libraryWorlds.removeAllItems()
            library.forEach { world -> view.homeScreen.libraryWorlds.addItem(
                if (world.worldId == null || world.versionId == null)
                    messages.text("home.libraryEmptyEntry", world.name, shortId(Path.of(world.path).fileName.toString()))
                else messages.text("home.libraryNamedEntry", world.name, shortId(world.worldId), shortId(world.versionId))
            ) }
            view.homeScreen.libraryWorlds.selectedIndex = displayedRepositories.indexOf(path)
            knownRepositories.clear()
            knownRepositories.addAll(displayedRepositories)
            saveSettings()
            view.homeScreen.statusDetail.text = if (current == null) messages.text("repository.sourceHint") else
                messages.text("repository.workingDirectory", repo.workingDirectory)
            view.homeScreen.playerHint.text = when {
                player == null -> ""
                player.legacyEmbeddedPlayer -> messages.text("player.legacyHint")
                player.singleplayerUuid && player.modernPlayerFiles == 0 -> messages.text("player.modernMissingHint")
                else -> messages.text("player.fileCount", (player.legacyPlayerFiles + player.modernPlayerFiles).toString())
            }
            view.status(messages.text("repository.updated"))
            if (view.friendsScreen.ownId.text.isBlank()) refreshFriends()
        }
    }

    fun openWorldFolder() {
        val path = view.repositoryField.text.trim().takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() }?.resolve("world")
        if (path == null || !Files.isDirectory(path)) { dialogs.warning(messages.text("repository.workMissing")); return }
        runCatching { java.awt.Desktop.getDesktop().open(path.toFile()) }
            .onFailure { dialogs.warning(messages.text("repository.openFolderFailed", it.message)) }
    }

    private fun repository(): WorldRepository? {
        val path = view.repositoryField.text.trim()
        if (path.isBlank()) { dialogs.warning(messages.text("repository.required")); return null }
        return runCatching { WorldRepository(Path.of(path)) }.getOrElse { dialogs.warning(it.message ?: messages.text("repository.openFailed")); null }
    }

    private fun handleError(failure: Throwable) {
        if (failure is WorldConflictException) {
            view.statusWarning(messages.text("conflict.saved"))
            if (dialogs.confirmIncomingVersion(failure.local, failure.incoming)) {
                val repository = repository() ?: return
                jobs.run(messages.text("conflict.activating"), { repository.checkout(failure.incoming) }) {
                    refreshStatus()
                    view.statusSuccess(messages.text("conflict.activated"))
                }
            }
            return
        }
        if (failure is TransferOperationException) {
            val cause = requireNotNull(failure.cause)
            val kind = TransferFailureClassifier.classify(cause)
            val key = when {
                cause is WorldTransferTimeoutException -> when (cause.phase) {
                    WorldTransferPhase.READINESS -> "network.error.readinessTimeout"
                    WorldTransferPhase.MANIFEST -> "network.error.manifestTimeout"
                    else -> "network.error.idleTimeout"
                }
                cause is RemoteTransferException && kind == TransferFailureKind.LOCAL_STORAGE -> "network.error.remoteStorage"
                else -> "network.error.${kind.name.lowercase()}"
            }
            val explanation = messages.text(key)
            val diagnostic = runCatching { TransferDiagnostics.record(failure.id, cause) }.getOrElse {
                System.err.println("EnderLink transfer [${failure.id}] ${cause.javaClass.name}: ${cause.message}; diagnostic write failed: ${it.message}")
                null
            }
            view.statusError(messages.text("network.error.status", failure.id.toString().take(8)))
            dialogs.error(messages.text("network.error.dialog", explanation, failure.id.toString(),
                diagnostic?.toString() ?: messages.text("network.error.diagnosticUnavailable")))
            return
        }
        val message = failure.message ?: failure.javaClass.simpleName
        view.statusError(messages.text("status.error", message))
        dialogs.error(message)
    }

    fun cancelOperation() {
        if (!jobs.isBusy) return
        if (installingMinecraft) { jobs.updateStatus(messages.text("minecraft.installFinishing")); return }
        dialogs.dismissIncomingWorld()
        jobs.updateStatus(messages.text("status.cancelling"))
        Thread({ operations.cancel() }, "enderlink-cancel").apply { isDaemon = true; start() }
    }

    private fun statusAsync(text: String) = jobs.updateStatus(text)

    fun saveSettings() {
        rememberWorldState()
        settings.save(DesktopPreferences(
            repository = view.repositoryField.text,
            libraryRoot = view.homeScreen.libraryRootField.text,
            saves = view.receiveScreen.savesField.text,
            saveName = view.receiveScreen.saveNameField.text,
            ownerUuid = ownerUuid,
            lanPlayerName = view.sendScreen.lanNameField.text,
            coordinatorUrl = operations.coordinatorUrl,
            serverBase = view.serverScreen.serverBaseField.text,
            jar = view.serverScreen.jarField.text,
            parentFingerprint = lastParentFingerprint,
            knownRepositories = knownRepositories.toList(),
            worldStates = worldStates.toMap(),
            chooserDirectories = chooserDirectories.toMap(),
        ))
    }

    private fun repositoryKey(path: String): String = path.takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize().toString() }.getOrDefault("") } ?: ""

    private fun rememberWorldState() {
        if (activeRepositoryPath.isNotBlank()) {
            worldStates[activeRepositoryPath] = WorldUiState(view.receiveScreen.saveNameField.text.trim(), lastParentFingerprint)
        }
    }

    private fun activateRepository(path: String, rememberCurrent: Boolean = true) {
        if (rememberCurrent) rememberWorldState()
        activeRepositoryPath = repositoryKey(path)
        view.repositoryField.text = path
        val state = worldStates[activeRepositoryPath]
        state?.let { view.receiveScreen.saveNameField.text = it.saveName }
        lastParentFingerprint = state?.parentFingerprint
    }

    private data class LibraryWorld(val path: String, val name: String, val worldId: String?, val versionId: String?)

    private fun availableSaveName(saves: Path, preferred: String, reserved: Set<String> = emptySet()): String {
        val base = preferred.trim().replace(Regex(" [0-9]+$"), "").ifBlank { "EnderLink World" }
        if (base !in reserved && !Files.exists(saves.resolve(base), NOFOLLOW_LINKS)) return base
        for (number in 2..10_000) {
            val candidate = "$base $number"
            if (candidate !in reserved && !Files.exists(saves.resolve(candidate), NOFOLLOW_LINKS)) return candidate
        }
        throw IOException(messages.text("minecraft.folder.required"))
    }
    private class TransferOperationException(val id: UUID, cause: IOException) : IOException(cause.message, cause)

    private fun selectedPlayer(field: javax.swing.JTextField): Pair<UUID, UUID?>? {
        val owner = selectedOwner(field) ?: return null
        val name = view.sendScreen.lanNameField.text.trim()
        val guest = if (name.isEmpty()) null else try { PlayerDataInspector.offlineUuid(name) }
        catch (_: IllegalArgumentException) {
            dialogs.warning(messages.text("player.lanNameInvalid"))
            return null
        }
        return owner to guest
    }

    private fun selectedOwner(field: javax.swing.JTextField): UUID? {
        val raw = field.text.trim().lowercase()
        val owner = runCatching { UUID.fromString(raw) }.getOrNull()
        if (owner == null || owner.toString() != raw) {
            dialogs.warning(messages.text("player.uuid.required"))
            return null
        }
        return owner
    }

    fun chooseDirectory(target: javax.swing.JTextField) {
        val purpose = when (target) {
            view.repositoryField -> DirectoryPurpose.REPOSITORY
            view.sendScreen.sourceField -> DirectoryPurpose.SOURCE
            view.receiveScreen.savesField -> DirectoryPurpose.SAVES
            view.serverScreen.serverBaseField -> DirectoryPurpose.SERVER
            else -> return
        }
        selectDirectory(purpose, target.text.takeIf(String::isNotBlank) ?: chooserDirectories[purpose.key].orEmpty())?.let {
            target.text = it
            saveSettings()
            if (target === view.repositoryField) refreshStatus()
        }
    }

    fun chooseFile(target: javax.swing.JTextField) {
        val initial = target.text.takeIf(String::isNotBlank) ?: chooserDirectories["jar"].orEmpty()
        val selected = dialogs.chooseJar(initial) ?: return
        val path = Path.of(selected)
        runCatching { DesktopPathSelection.jar(path) }.onFailure {
            dialogs.warning(it.message ?: messages.text("fileChooser.jarRequired")); return
        }
        chooserDirectories["jar"] = path.parent.toString()
        target.text = selected
        saveSettings()
    }

    private fun selectDirectory(purpose: DirectoryPurpose, initial: String): String? {
        val selected = dialogs.chooseDirectory(initial) ?: return null
        val path = Path.of(selected)
        runCatching {
            DesktopPathSelection.directory(purpose, path,
                pathOrNull(view.homeScreen.libraryRootField.text),
                pathOrNull(view.receiveScreen.savesField.text),
                pathOrNull(view.repositoryField.text))
        }.onFailure {
            dialogs.warning(it.message ?: messages.text("fileChooser.directoryRequired"))
            return null
        }
        chooserDirectories[purpose.key] = selected
        return selected
    }

    private fun pathOrNull(raw: String): Path? = raw.trim().takeIf(String::isNotEmpty)
        ?.let { runCatching { Path.of(it) }.getOrNull() }
}
