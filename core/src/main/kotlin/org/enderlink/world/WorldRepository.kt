package org.enderlink.world

import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

class WorldConflictException(val local: String?, val incoming: String) : IOException(
    "Конфликт версий мира: локальная ${local ?: "отсутствует"}, входящая $incoming"
)

data class WorldUpdatePlan(val createOrReplace: List<WorldEntry>, val delete: List<String>)

/** База содержит рабочий `world` и скрытый каталог `.enderlink` с историей. */
class WorldRepository(base: Path) {
    private val root = base.toAbsolutePath().normalize()
    private val work = root.resolve("world")
    private val meta = root.resolve(".enderlink")
    private val objects = meta.resolve("objects")
    private val versions = meta.resolve("versions")
    private val staging = meta.resolve("staging")
    private val previous = meta.resolve("previous")
    private val active = meta.resolve("active")
    private val pending = meta.resolve("pending")

    init {
        if (Files.isSymbolicLink(root)) throw IOException("Каталог хранилища не должен быть ссылкой")
        Files.createDirectories(root)
        for (directory in listOf(meta, objects, versions, staging)) {
            if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                throw IOException("Ожидается обычный каталог: $directory")
            }
            Files.createDirectories(directory)
        }
    }

    val workingDirectory: Path get() = work

    fun current(): WorldManifest? = locked { recoverLocked(); readActive() }

    fun load(versionId: String): WorldManifest = locked { loadManifest(versionId) }

    fun verifyCurrent() = locked {
        recoverLocked()
        val manifest = readActive() ?: throw IOException("Мир ещё не опубликован")
        if (!matchesWorking(manifest)) throw IOException("Рабочая копия мира отличается от опубликованной версии")
    }

    fun record(manifest: WorldManifest) = locked {
        manifest.validate()
        val local = readActive()
        if (local != null && local.worldId != manifest.worldId) throw IOException("Это другой мир")
        saveManifest(manifest)
    }

    fun hasObject(entry: WorldEntry): Boolean = locked {
        requireFile(entry)
        matchesObject(objectPath(entry.hash!!), entry)
    }

    fun openObject(entry: WorldEntry): InputStream = locked {
        requireFile(entry)
        val path = objectPath(entry.hash!!)
        if (!matchesObject(path, entry)) throw IOException("Объект повреждён или отсутствует: ${entry.hash}")
        Files.newInputStream(path, NOFOLLOW_LINKS)
    }

    fun storeObject(entry: WorldEntry, source: InputStream) = locked {
        requireFile(entry)
        putObject(entry, source, skipExisting = false)
    }

    fun plan(target: WorldManifest): WorldUpdatePlan = locked {
        recoverLocked()
        target.validate()
        val old = readActive()?.entries.orEmpty().associateBy { it.path }
        val next = target.entries.associateBy { it.path }
        WorldUpdatePlan(target.entries.filter { old[it.path] != it }, old.keys.filter { it !in next }.sorted())
    }

    /** Публикация закрытого мира. При публикации рабочего каталога игра уже могла изменить его байты. */
    fun publish(source: Path, worldId: String? = null): WorldManifest = locked {
        recoverLocked()
        val old = readActive()
        if (old != null && worldId != null && old.worldId != worldId) throw IOException("ID мира не совпадает")
        val id = old?.worldId ?: worldId ?: WorldManifest.newWorldId()
        val sourceRoot = source.toAbsolutePath().normalize()
        val manifest = WorldScanner.scan(sourceRoot, id, old?.versionId)
        if (old != null && old.treeHash == manifest.treeHash) return@locked old
        for (entry in manifest.entries.filter { it.type == WorldEntryType.FILE }) {
            putObject(entry, Files.newInputStream(sourceRoot.resolve(entry.path), NOFOLLOW_LINKS))
        }
        saveManifest(manifest)
        if (sourceRoot == work) {
            if (WorldScanner.scan(work, id, old?.versionId) != manifest) throw IOException("Мир изменился во время публикации")
            writeMarker(active, manifest.versionId)
        }
        else activate(manifest, old, allowBranch = false)
        manifest
    }

    /** Переносит только отсутствующие объекты из другой локальной базы. */
    fun importFrom(other: WorldRepository, manifest: WorldManifest) = locked {
        recoverLocked()
        manifest.validate()
        val local = readActive()
        if (local != null && local.worldId != manifest.worldId) throw IOException("Это другой мир")
        for (entry in manifest.entries.filter { it.type == WorldEntryType.FILE }) {
            putObject(entry, Files.newInputStream(other.objectPath(entry.hash!!), NOFOLLOW_LINKS))
        }
        saveManifest(manifest)
    }

    fun activate(manifest: WorldManifest) = locked {
        recoverLocked()
        manifest.validate()
        val old = readActive()
        if (old?.versionId != manifest.versionId) activate(manifest, old, allowBranch = false)
    }

    /** Явное восстановление исторической версии; следующая публикация продолжит именно её ветку. */
    fun checkout(versionId: String) = locked {
        recoverLocked()
        val target = loadManifest(versionId)
        val old = readActive()
        if (old != null && old.worldId != target.worldId) throw IOException("Это другой мир")
        if (old?.versionId != target.versionId) activate(target, old, allowBranch = true)
    }

    fun recover() = locked { recoverLocked() }

    private fun activate(target: WorldManifest, old: WorldManifest?, allowBranch: Boolean) {
        if (old != null && old.worldId != target.worldId) throw IOException("Это другой мир")
        if (!allowBranch && old != null && old.versionId != target.parentVersionId) {
            throw WorldConflictException(old.versionId, target.versionId)
        }
        if (old != null && !matchesWorking(old)) throw IOException("Рабочий мир изменён после публикации; сначала опубликуйте его отдельной версией")
        if (old == null && Files.exists(work, NOFOLLOW_LINKS)) throw IOException("Рабочий каталог уже существует без версии")
        if (loadManifest(target.versionId) != target) throw IOException("Манифест не сохранён")
        val stage = staging.resolve(target.versionId.removePrefix("sha256:"))
        if (Files.exists(stage, NOFOLLOW_LINKS)) deleteTree(stage)
        Files.createDirectory(stage)
        for (entry in target.entries) {
            val destination = stage.resolve(entry.path)
            when (entry.type) {
                WorldEntryType.DIRECTORY -> Files.createDirectory(destination)
                WorldEntryType.FILE -> {
                    val objectPath = objectPath(entry.hash!!)
                    if (!matchesObject(objectPath, entry)) throw IOException("Объект повреждён или отсутствует: ${entry.hash}")
                    Files.newInputStream(objectPath, NOFOLLOW_LINKS).use { input ->
                        Files.newOutputStream(destination).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                }
            }
        }
        if (WorldScanner.scan(stage, target.worldId, target.parentVersionId) != target) {
            throw IOException("Собранная версия не совпадает с манифестом")
        }
        if (Files.exists(previous, NOFOLLOW_LINKS)) deleteTree(previous)
        writeMarker(pending, target.versionId)
        if (Files.exists(work, NOFOLLOW_LINKS)) Files.move(work, previous, ATOMIC_MOVE)
        Files.move(stage, work, ATOMIC_MOVE)
        writeMarker(active, target.versionId)
        Files.deleteIfExists(pending)
    }

    private fun recoverLocked() {
        if (!Files.exists(pending, NOFOLLOW_LINKS)) return
        val target = loadManifest(readMarker(pending))
        val currentId = if (Files.exists(active, NOFOLLOW_LINKS)) readMarker(active) else null
        if (currentId == target.versionId || matchesWorking(target)) {
            writeMarker(active, target.versionId)
        } else if (Files.exists(previous, NOFOLLOW_LINKS)) {
            if (Files.exists(work, NOFOLLOW_LINKS)) {
                Files.move(work, staging.resolve("recovery-${UUID.randomUUID()}"), ATOMIC_MOVE)
            }
            Files.move(previous, work, ATOMIC_MOVE)
        } else if (!Files.exists(work, NOFOLLOW_LINKS)) {
            throw IOException("Нельзя восстановить рабочую версию: нет предыдущего дерева")
        }
        Files.deleteIfExists(pending)
    }

    private fun matchesWorking(manifest: WorldManifest): Boolean = try {
        Files.isDirectory(work, NOFOLLOW_LINKS) && WorldScanner.scan(work, manifest.worldId, manifest.parentVersionId) == manifest
    } catch (_: IOException) { false }

    private fun putObject(entry: WorldEntry, source: InputStream, skipExisting: Boolean = true) {
        source.use { input ->
            val path = objectPath(entry.hash!!)
            if (skipExisting && Files.exists(path, NOFOLLOW_LINKS) && matchesObject(path, entry)) return
            val temp = Files.createTempFile(objects, "incoming-", ".tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size = Math.addExact(size, count.toLong())
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                if (size != entry.size || "sha256:" + digest.digest().toHex() != entry.hash) {
                    throw IOException("Содержимое объекта не совпадает с манифестом: ${entry.path}")
                }
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
    }

    private fun matchesObject(path: Path, entry: WorldEntry): Boolean {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != entry.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256:" + digest.digest().toHex() == entry.hash
    }

    private fun requireFile(entry: WorldEntry) {
        if (entry.type != WorldEntryType.FILE || entry.size < 0 || entry.hash == null) throw IOException("Ожидается файл")
        WorldManifest.requireHash(entry.hash)
    }

    private fun objectPath(hash: String): Path {
        WorldManifest.requireHash(hash)
        return objects.resolve(hash.removePrefix("sha256:"))
    }

    private fun saveManifest(manifest: WorldManifest) {
        val path = manifestPath(manifest.versionId)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (WorldManifestIO.read(path) != manifest) throw IOException("Сохранённый манифест не совпадает")
            return
        }
        val temp = Files.createTempFile(versions, "version-", ".tmp")
        try {
            WorldManifestIO.write(temp, manifest)
            Files.move(temp, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun loadManifest(versionId: String): WorldManifest {
        val manifest = WorldManifestIO.read(manifestPath(versionId))
        if (manifest.versionId != versionId) throw IOException("ID манифеста не совпадает")
        return manifest
    }

    private fun manifestPath(versionId: String): Path {
        WorldManifest.requireHash(versionId)
        return versions.resolve(versionId.removePrefix("sha256:") + ".manifest")
    }

    private fun readActive(): WorldManifest? = if (Files.exists(active, NOFOLLOW_LINKS)) loadManifest(readMarker(active)) else null

    private fun readMarker(path: Path): String {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 100) throw IOException("Некорректный указатель версии")
        val id = Files.readString(path).trim()
        WorldManifest.requireHash(id)
        return id
    }

    private fun writeMarker(path: Path, versionId: String) {
        WorldManifest.requireHash(versionId)
        val temp = Files.createTempFile(meta, "marker-", ".tmp")
        try {
            Files.writeString(temp, "$versionId\n")
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun deleteTree(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun <T> locked(action: () -> T): T {
        FileChannel.open(meta.resolve("lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { return action() }
        }
    }
}
