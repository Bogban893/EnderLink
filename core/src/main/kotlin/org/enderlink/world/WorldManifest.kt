package org.enderlink.world

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

enum class WorldEntryType { DIRECTORY, FILE }

data class WorldEntry(val path: String, val type: WorldEntryType, val size: Long = 0, val hash: String? = null)

data class WorldManifest(
    val formatVersion: Int,
    val worldId: String,
    val versionId: String,
    val parentVersionId: String?,
    val treeHash: String,
    val entries: List<WorldEntry>,
) {
    fun validate() {
        if (formatVersion != FORMAT_VERSION) throw IOException("Неподдерживаемый формат мира: $formatVersion")
        try {
            if (UUID.fromString(worldId).toString() != worldId) throw IllegalArgumentException()
        } catch (_: IllegalArgumentException) { throw IOException("Некорректный ID мира") }
        parentVersionId?.let { requireHash(it) }
        requireHash(treeHash)
        requireHash(versionId)
        var previous: String? = null
        val types = mutableMapOf<String, WorldEntryType>()
        val objectSizes = mutableMapOf<String, Long>()
        for (entry in entries) {
            validatePath(entry.path)
            if (previous != null && previous >= entry.path) throw IOException("Манифест не отсортирован или содержит повтор пути")
            previous = entry.path
            val parent = entry.path.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && types[parent] != WorldEntryType.DIRECTORY) throw IOException("Нет родительского каталога: ${entry.path}")
            types[entry.path] = entry.type
            when (entry.type) {
                WorldEntryType.DIRECTORY -> if (entry.size != 0L || entry.hash != null) throw IOException("Некорректный каталог")
                WorldEntryType.FILE -> {
                    if (entry.size < 0 || entry.hash == null) throw IOException("Некорректный файл")
                    requireHash(entry.hash)
                    val knownSize = objectSizes.putIfAbsent(entry.hash, entry.size)
                    if (knownSize != null && knownSize != entry.size) throw IOException("Один хеш имеет разные размеры")
                }
            }
        }
        if (treeHash != treeHash(entries)) throw IOException("Хеш дерева не совпадает")
        if (versionId != versionId(worldId, parentVersionId, treeHash)) throw IOException("ID версии не совпадает")
    }

    companion object {
        const val FORMAT_VERSION = 1
        private val hashPattern = Regex("sha256:[0-9a-f]{64}")

        fun newWorldId(): String = UUID.randomUUID().toString()

        fun create(worldId: String, parentVersionId: String?, entries: List<WorldEntry>): WorldManifest {
            val sorted = entries.sortedBy { it.path }
            for (entry in sorted) {
                validatePath(entry.path)
                if (entry.type == WorldEntryType.FILE && (entry.size < 0 || entry.hash == null)) throw IOException("Некорректный файл")
            }
            val tree = treeHash(sorted)
            return WorldManifest(FORMAT_VERSION, worldId, versionId(worldId, parentVersionId, tree), parentVersionId, tree, sorted).also { it.validate() }
        }

        internal fun requireHash(value: String) {
            if (!hashPattern.matches(value)) throw IOException("Некорректный SHA-256: $value")
        }

        internal fun validatePath(path: String) {
            if (path.isEmpty() || path.split('/').any { part ->
                    part.isEmpty() || part == "." || part == ".." || part.endsWith('.') || part.endsWith(' ') ||
                        part.any { it in "\\:<>\"|?*" || it.isISOControl() } ||
                        part.substringBefore('.').uppercase() in
                        (setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") })
                }) throw IOException("Недопустимый путь мира: $path")
        }

        private fun treeHash(entries: List<WorldEntry>): String = digest("enderlink-tree-v1") {
            writeInt(entries.size)
            for (entry in entries) {
                writeString(entry.path)
                writeByte(if (entry.type == WorldEntryType.DIRECTORY) 0 else 1)
                if (entry.type == WorldEntryType.FILE) {
                    writeLong(entry.size)
                    writeString(entry.hash!!)
                }
            }
        }

        private fun versionId(worldId: String, parent: String?, tree: String): String = digest("enderlink-version-v1") {
            writeInt(FORMAT_VERSION)
            writeString(worldId)
            writeBoolean(parent != null)
            if (parent != null) writeString(parent)
            writeString(tree)
        }

        private fun digest(domain: String, body: DataOutputStream.() -> Unit): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeString(domain); it.body() }
            return "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
        }

        private fun DataOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
    }
}

/** Источник должен быть закрыт для игры и неизменен на всё время сканирования и публикации. */
object WorldScanner {
    fun scan(root: Path, worldId: String, parentVersionId: String? = null): WorldManifest {
        val directory = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw IOException("Не найден обычный каталог мира: $root")
        val entries = mutableListOf<WorldEntry>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            private fun relative(path: Path): String = directory.relativize(path).joinToString("/") { it.toString() }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != directory) entries.add(WorldEntry(relative(dir), WorldEntryType.DIRECTORY))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) throw IOException("Ссылка или специальный файл в мире: $file")
                val hash = MessageDigest.getInstance("SHA-256")
                var size = 0L
                Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size = Math.addExact(size, count.toLong())
                        hash.update(buffer, 0, count)
                    }
                }
                if (size != attrs.size() || Files.size(file) != size) throw IOException("Файл изменился при сканировании: $file")
                entries.add(WorldEntry(relative(file), WorldEntryType.FILE, size, "sha256:" + hash.digest().toHex()))
                return FileVisitResult.CONTINUE
            }
        })
        return WorldManifest.create(worldId, parentVersionId, entries)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
