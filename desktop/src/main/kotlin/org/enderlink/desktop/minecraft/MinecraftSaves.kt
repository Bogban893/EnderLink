package org.enderlink.desktop.minecraft

import org.enderlink.desktop.i18n.messages
import org.enderlink.world.WorldEntry
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldScanner
import org.enderlink.world.PlayerProfileChoice
import org.enderlink.world.PlayerProfileConflictException
import org.enderlink.worldtransfer.ParentFingerprint
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID

data class MinecraftInstallResult(val directory: Path, val versionId: String, val replacedFiles: Int, val reusedFiles: Int)
enum class ManagedSaveState { UNCHANGED, CHANGED, OPEN }
data class ManagedMinecraftSave(val directory: Path, val state: ManagedSaveState)

/** Installs a verified EnderLink version as an ordinary Minecraft Java save. */
object MinecraftSaves {
    private data class InstalledMarker(
        val manifest: WorldManifest, val contentHash: String? = null, val owner: UUID? = null, val guest: UUID? = null,
    )
    fun defaultDirectory(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            "win" in os -> System.getenv("APPDATA")?.let(Path::of)?.resolve(".minecraft/saves")
                ?: home.resolve("AppData/Roaming/.minecraft/saves")
            "mac" in os -> home.resolve("Library/Application Support/minecraft/saves")
            else -> home.resolve(".minecraft/saves")
        }
    }

    /** Checks Minecraft's session lock before reading a save for publication. */
    fun requireClosed(world: Path) {
        val lockPath = world.resolve("session.lock")
        if (!Files.exists(lockPath, NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.lockSymlink"))
        FileChannel.open(lockPath, READ, WRITE).use { channel ->
            val lock = trySaveLock(channel)
                ?: throw IOException(messages.text("minecraft.openBeforeReceive"))
            lock.use { }
        }
    }

    /** Reads tracked saves for the active world without changing them. Call off the Swing event thread. */
    fun inspectManaged(repository: WorldRepository, savesDirectory: Path): List<ManagedMinecraftSave> {
        val current = repository.current() ?: return emptyList()
        val saves = savesDirectory.toAbsolutePath().normalize()
        val repositoryRoot = repository.workingDirectory.parent
        if (saves.startsWith(repositoryRoot) || repositoryRoot.startsWith(saves)) {
            throw IOException(messages.text("minecraft.repositoryOverlap"))
        }
        if (Files.isSymbolicLink(saves)) throw IOException(messages.text("minecraft.savesSymlink"))
        val metadata = saves.resolve(".enderlink-sync")
        if (!Files.exists(metadata, NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isDirectory(metadata, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.metadataSymlink"))
        return Files.list(metadata).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".properties") }.map { marker ->
                if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.invalidMarkerPath", marker))
                val properties = Properties().apply { Files.newInputStream(marker).use(::load) }
                if (properties.getProperty("worldId") != current.worldId) return@map null
                val installed = readMarker(repository, marker)
                val name = marker.fileName.toString().removeSuffix(".properties")
                validateName(name)
                val target = saves.resolve(name)
                val lockPath = target.resolve("session.lock")
                val state = when {
                    !Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target) -> ManagedSaveState.CHANGED
                    !Files.isRegularFile(lockPath, NOFOLLOW_LINKS) -> ManagedSaveState.CHANGED
                    else -> FileChannel.open(lockPath, READ, WRITE).use { channel ->
                        val lock = trySaveLock(channel)
                        if (lock == null) ManagedSaveState.OPEN
                        else lock.use { if (matches(target, installed)) ManagedSaveState.UNCHANGED else ManagedSaveState.CHANGED }
                    }
                }
                ManagedMinecraftSave(target, state)
            }.filter { it != null }.map { requireNotNull(it) }.toList()
        }
    }

    /** Seeds the receiver's object store from a closed save, allowing a first transfer to reuse it. */
    fun cacheExisting(repository: WorldRepository, savesDirectory: Path, folderName: String): Int {
        validateName(folderName)
        val saves = savesDirectory.toAbsolutePath().normalize()
        val target = saves.resolve(folderName)
        if (saves.startsWith(repository.workingDirectory.parent) || repository.workingDirectory.parent.startsWith(saves)) {
            throw IOException(messages.text("minecraft.repositoryOverlap"))
        }
        if (!Files.exists(target, NOFOLLOW_LINKS)) return 0
        if (!Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw IOException(messages.text("minecraft.existingNotDirectory"))
        }
        val session = target.resolve("session.lock")
        if (Files.isSymbolicLink(session)) throw IOException(messages.text("minecraft.lockSymlink"))
        FileChannel.open(session, CREATE, READ, WRITE).use { channel ->
            val lock = trySaveLock(channel)
                ?: throw IOException(messages.text("minecraft.openBeforeReceive"))
            lock.use { return repository.cacheObjectsFrom(target) }
        }
    }

    fun install(
        repository: WorldRepository, savesDirectory: Path, folderName: String,
        parentFingerprint: ParentFingerprint? = null,
        owner: UUID? = null,
        guest: UUID? = null,
        resolvePlayerConflict: (PlayerProfileConflictException) -> PlayerProfileChoice = { throw it },
    ): MinecraftInstallResult {
        require(guest == null || owner != null) { "Для LAN-профиля нужен UUID владельца" }
        validateName(folderName)
        repository.verifyCurrent()
        val incoming = repository.current() ?: throw IOException(messages.text("minecraft.noVersion"))
        val saves = savesDirectory.toAbsolutePath().normalize()
        val repositoryRoot = repository.workingDirectory.parent
        if (saves.startsWith(repositoryRoot) || repositoryRoot.startsWith(saves)) {
            throw IOException(messages.text("minecraft.repositoryOverlap"))
        }
        if (Files.isSymbolicLink(saves)) throw IOException(messages.text("minecraft.savesSymlink"))
        Files.createDirectories(saves)
        val metadata = saves.resolve(".enderlink-sync")
        if (Files.isSymbolicLink(metadata)) throw IOException(messages.text("minecraft.metadataSymlink"))
        Files.createDirectories(metadata)
        val target = saves.resolve(folderName)
        val stage = metadata.resolve("$folderName.stage")
        val backup = metadata.resolve("$folderName.backup")
        val marker = metadata.resolve("$folderName.properties")
        val pending = metadata.resolve("$folderName.pending")
        FileChannel.open(metadata.resolve("sync.lock"), CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IOException(messages.text("minecraft.syncBusy"))
            lock.use {
                recover(repository, target, stage, backup, marker, pending)
                if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isDirectory(target, NOFOLLOW_LINKS)) {
                    throw IOException(messages.text("minecraft.targetOccupied", target))
                }
                val previous = findPrevious(repository, target, marker, incoming, parentFingerprint)
                val oldEntries = previous?.manifest?.entries.orEmpty().filterNot(::isSessionLock).associateBy { it.path }
                val newEntries = incoming.entries.filterNot(::isSessionLock)
                val changedFiles = newEntries.count { oldEntries[it.path] != it }
                val replaced = if (previous != null && (previous.owner != owner || previous.guest != guest) && owner != null) maxOf(changedFiles, 1) else changedFiles
                val reused = newEntries.count { oldEntries[it.path] == it }
                if (previous?.manifest?.versionId == incoming.versionId && previous.owner == owner && previous.guest == guest) {
                    writeMarker(marker, previous)
                    return MinecraftInstallResult(target, incoming.versionId, 0, reused)
                }
                if (Files.exists(stage, NOFOLLOW_LINKS)) deleteTree(stage)
                copyTree(repository.workingDirectory, stage)
                if (!matches(stage, incoming)) {
                    deleteTree(stage)
                    throw IOException(messages.text("minecraft.copyChanged"))
                }
                val playerData = org.enderlink.world.PlayerDataInspector.inspect(stage)
                if (owner == null && (playerData.legacyEmbeddedPlayer || playerData.singleplayerUuid)) {
                    throw IOException(messages.text("minecraft.ownerRequired"))
                }
                if (owner != null) org.enderlink.world.PlayerDataInspector.prepareSingleplayerOwner(stage, owner, guest, resolvePlayerConflict)
                val installed = InstalledMarker(incoming, if (owner == null) null else contentHash(stage, incoming), owner, guest)
                // Windows cannot rename directories with open files. Other platforms keep the lock across rename.
                FileChannel.open(stage.resolve("session.lock"), CREATE, READ, WRITE).use { stageChannel ->
                    val stageLock = trySaveLock(stageChannel)
                        ?: throw IOException(messages.text("minecraft.stageLockFailed"))
                    try {
                        closeForWindowsRename(stageChannel)
                        writeMarker(pending, installed)
                        if (Files.exists(target, NOFOLLOW_LINKS)) {
                            if (Files.isSymbolicLink(target.resolve("session.lock"))) {
                                throw IOException(messages.text("minecraft.lockSymlink"))
                            }
                            FileChannel.open(target.resolve("session.lock"), CREATE, READ, WRITE).use { oldChannel ->
                                val oldLock = trySaveLock(oldChannel)
                                    ?: throw IOException(messages.text("minecraft.openBeforeInstall"))
                                try {
                                    if (!matches(target, requireNotNull(previous))) {
                                        throw IOException(messages.text("minecraft.localChanges"))
                                    }
                                    // An intervening Minecraft open prevents the directory rename on Windows.
                                    closeForWindowsRename(oldChannel)
                                    move(target, backup)
                                    try { move(stage, target) }
                                    catch (error: Exception) { move(backup, target); throw error }
                                } finally { if (oldLock.isValid) oldLock.release() }
                            }
                        } else move(stage, target)
                        writeMarker(marker, installed)
                        Files.deleteIfExists(pending)
                    } finally { if (stageLock.isValid) stageLock.release() }
                }
                if (Files.exists(backup, NOFOLLOW_LINKS)) deleteTree(backup)
                return MinecraftInstallResult(target, incoming.versionId, replaced, reused)
            }
        }
    }

    private fun trySaveLock(channel: FileChannel): FileLock? = try {
        // Overlaps Minecraft's whole-file lock while allowing Windows to read session.lock during hashing.
        channel.tryLock(Long.MAX_VALUE - 1, 1, false)
    } catch (_: OverlappingFileLockException) { null }

    private fun closeForWindowsRename(channel: FileChannel) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) channel.close()
    }

    private fun findPrevious(
        repository: WorldRepository, target: Path, marker: Path, incoming: WorldManifest,
        parentFingerprint: ParentFingerprint?,
    ): InstalledMarker? {
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            if (Files.exists(marker, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.targetMissing", target))
            return null
        }
        if (Files.isSymbolicLink(target)) throw IOException(messages.text("minecraft.worldSymlink"))
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.invalidMarker"))
            val properties = Properties().apply { Files.newInputStream(marker).use(::load) }
            if (properties.getProperty("worldId") != incoming.worldId) throw IOException(messages.text("minecraft.differentWorld"))
            val savedVersion = properties.getProperty("versionId") ?: throw IOException(messages.text("minecraft.previousVersionMissing"))
            val previous = runCatching { repository.load(savedVersion) }.getOrNull()
                ?: matchingFingerprint(target, incoming, parentFingerprint?.takeIf { it.versionId == savedVersion })
                ?: throw IOException(messages.text("minecraft.previousManifestMissing"))
            val installed = InstalledMarker(previous,
                properties.getProperty("contentHash")?.also(::requireContentHash),
                properties.getProperty("ownerUuid")?.let(UUID::fromString),
                properties.getProperty("guestUuid")?.let(UUID::fromString))
            if (previous.worldId != incoming.worldId || !matches(target, installed)) {
                throw IOException(messages.text("minecraft.localChanges"))
            }
            return installed
        }
        // A manually copied EnderLink world can be adopted only when its bytes match a known version.
        val candidates = listOfNotNull(incoming, incoming.parentVersionId?.let { runCatching { repository.load(it) }.getOrNull() })
        return candidates.firstOrNull { matches(target, it) }?.let { InstalledMarker(it) }
            ?: matchingFingerprint(target, incoming, parentFingerprint)?.let { InstalledMarker(it) }
            ?: throw IOException(messages.text("minecraft.unrelatedTarget", target))
    }

    private fun matchingFingerprint(
        target: Path, incoming: WorldManifest, hint: ParentFingerprint?,
    ): WorldManifest? {
        if (hint == null || hint.versionId != incoming.parentVersionId ||
            hint.formatVersion !in 1..WorldManifest.FORMAT_VERSION) return null
        val scanned = try { WorldScanner.scan(target, incoming.worldId, formatVersion = hint.formatVersion) }
            catch (_: IOException) { return null }
        val normalized = WorldManifest.create(incoming.worldId, null,
            scanned.entries.filterNot(::isSessionLock), hint.formatVersion).treeHash
        return scanned.takeIf { normalized == hint.contentHash }
    }

    private fun recover(repository: WorldRepository, target: Path, stage: Path, backup: Path, marker: Path, pending: Path) {
        val transaction = if (Files.exists(pending, NOFOLLOW_LINKS)) readMarker(repository, pending) else null
        if (Files.exists(backup, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(backup, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.invalidBackup", backup))
            if (!Files.exists(target, NOFOLLOW_LINKS)) {
                move(backup, target)
                Files.deleteIfExists(pending)
            } else if (transaction != null && matches(target, transaction)) {
                writeMarker(marker, transaction)
                deleteTree(backup)
                Files.deleteIfExists(pending)
            } else if (Files.exists(marker, NOFOLLOW_LINKS) && matches(target, readMarker(repository, marker))) {
                deleteTree(backup)
                Files.deleteIfExists(pending)
            } else {
                throw IOException(messages.text("minecraft.interruptedUpdate", target, backup))
            }
        } else if (transaction != null) {
            if (Files.exists(target, NOFOLLOW_LINKS) && matches(target, transaction)) writeMarker(marker, transaction)
            Files.deleteIfExists(pending)
        }
        if (Files.exists(stage, NOFOLLOW_LINKS)) deleteTree(stage)
    }

    private fun readMarker(repository: WorldRepository, path: Path): InstalledMarker {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException(messages.text("minecraft.invalidMarkerPath", path))
        val properties = Properties().apply { Files.newInputStream(path).use(::load) }
        val manifest = repository.load(properties.getProperty("versionId") ?: throw IOException(messages.text("minecraft.markerVersionMissing", path)))
        if (manifest.worldId != properties.getProperty("worldId")) throw IOException(messages.text("minecraft.worldIdMismatch"))
        return InstalledMarker(manifest,
            properties.getProperty("contentHash")?.also(::requireContentHash),
            properties.getProperty("ownerUuid")?.let(UUID::fromString),
            properties.getProperty("guestUuid")?.let(UUID::fromString))
    }

    private fun matches(directory: Path, marker: InstalledMarker): Boolean =
        if (marker.contentHash == null) matches(directory, marker.manifest)
        else runCatching { contentHash(directory, marker.manifest) == marker.contentHash }.getOrDefault(false)

    private fun contentHash(directory: Path, manifest: WorldManifest): String {
        val scanned = WorldScanner.scan(directory, manifest.worldId, manifest.parentVersionId, manifest.formatVersion)
        return WorldManifest.create(manifest.worldId, null,
            scanned.entries.filterNot(::isSessionLock), manifest.formatVersion).treeHash
    }

    private fun requireContentHash(value: String) {
        if (!value.matches(Regex("sha256:[0-9a-f]{64}"))) throw IOException(messages.text("minecraft.invalidMarker"))
    }

    private fun matches(directory: Path, manifest: WorldManifest): Boolean = try {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) false
        else WorldScanner.scan(directory, manifest.worldId, manifest.parentVersionId, manifest.formatVersion)
            .entries.filterNot(::isSessionLock) == manifest.entries.filterNot(::isSessionLock)
    } catch (_: IOException) { false }

    private fun isSessionLock(entry: WorldEntry): Boolean = entry.path == "session.lock"

    private fun writeMarker(path: Path, marker: InstalledMarker) {
        val temp = Files.createTempFile(path.parent, ".enderlink-marker-", ".tmp")
        try {
            Properties().apply {
                setProperty("worldId", marker.manifest.worldId)
                setProperty("versionId", marker.manifest.versionId)
                marker.contentHash?.let { setProperty("contentHash", it) }
                marker.owner?.let { setProperty("ownerUuid", it.toString()) }
                marker.guest?.let { setProperty("guestUuid", it.toString()) }
            }.also { properties -> Files.newOutputStream(temp).use { properties.store(it, "EnderLink Minecraft save") } }
            move(temp, path, replace = true)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            name.none { it in "\\/:<>\"|?*" || it.isISOControl() } && !name.endsWith('.') &&
            !name.startsWith(".enderlink") && name.length <= 80) {
            messages.text("minecraft.folder.invalid")
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.createDirectory(destination)
        try {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isDirectory) throw IOException(messages.text("minecraft.unusualDirectory", dir))
                    if (dir != source) Files.createDirectory(destination.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) throw IOException(messages.text("minecraft.unsupportedLink", file))
                    Files.copy(file, destination.resolve(source.relativize(file)), NOFOLLOW_LINKS)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (error: Exception) { deleteTree(destination); throw error }
    }

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    private fun move(source: Path, destination: Path, replace: Boolean = false) {
        val options = if (replace) arrayOf(ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) else arrayOf(ATOMIC_MOVE)
        try { Files.move(source, destination, *options) }
        catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            else Files.move(source, destination)
        }
    }
}
