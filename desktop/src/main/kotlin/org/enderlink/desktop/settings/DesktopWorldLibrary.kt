package org.enderlink.desktop.settings

import org.enderlink.desktop.i18n.messages
import org.enderlink.world.WorldRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Manages repository locations; world bytes and versions remain owned by WorldRepository. */
internal class DesktopWorldLibrary(private val root: Path) {
    val directory: Path = root.toAbsolutePath().normalize()

    fun prepare(saves: Path) {
        val savesPath = saves.toAbsolutePath().normalize()
        val actualLibrary = effectivePath(directory)
        val actualSaves = effectivePath(savesPath)
        if (actualLibrary.startsWith(actualSaves) || actualSaves.startsWith(actualLibrary)) {
            throw IOException(messages.text("library.overlap"))
        }
        if (Files.isSymbolicLink(directory)) throw IOException(messages.text("library.symlink"))
        if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw IOException(messages.text("library.pathOccupied", directory))
        }
        Files.createDirectories(directory)
    }

    private fun effectivePath(path: Path): Path {
        var existing = path
        while (!Files.exists(existing, NOFOLLOW_LINKS)) {
            existing = existing.parent ?: return path
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize()
    }

    /** Creates a fresh repository. The UUID folder remains stable after a world is received. */
    fun create(): Path {
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { messages.text("library.notReady") }
        while (true) {
            val candidate = directory.resolve(UUID.randomUUID().toString())
            try { Files.createDirectory(candidate) }
            catch (_: java.nio.file.FileAlreadyExistsException) { continue }
            WorldRepository(candidate)
            return candidate
        }
    }

    fun repositories(): List<Path> {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { entries ->
            entries.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) &&
                Files.isDirectory(it.resolve(".enderlink"), NOFOLLOW_LINKS) }
                .sorted().toList()
        }
    }

    /** Copies managed repositories to another location and verifies the active versions before switching callers. */
    fun copyTo(destination: DesktopWorldLibrary, saves: Path): Map<Path, Path> {
        if (directory == destination.directory) return repositories().associateWith { it }
        if (directory.startsWith(destination.directory) || destination.directory.startsWith(directory)) {
            throw IOException(messages.text("library.nested"))
        }
        destination.prepare(saves)
        if (directory.toRealPath().startsWith(destination.directory.toRealPath()) ||
            destination.directory.toRealPath().startsWith(directory.toRealPath())) {
            throw IOException(messages.text("library.nested"))
        }
        val sources = repositories()
        val mapping = sources.associateWith { destination.directory.resolve(it.fileName) }
        mapping.values.forEach { target ->
            if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException(messages.text("library.destinationOccupied", target))
        }
        val stage = Files.createTempDirectory(destination.directory, ".copy-")
        val committed = mutableListOf<Path>()
        try {
            for (source in sources) {
                val sourceRepository = WorldRepository(source)
                val before = sourceRepository.current()
                if (before != null) sourceRepository.verifyCurrent()
                val copy = stage.resolve(source.fileName)
                copyTree(source, copy)
                compareTree(source, copy)
                val after = sourceRepository.current()
                if (after?.versionId != before?.versionId) throw IOException(messages.text("library.changedDuringCopy", source))
                if (after != null) sourceRepository.verifyCurrent()
                val copiedRepository = WorldRepository(copy)
                if (copiedRepository.current()?.versionId != before?.versionId) {
                    throw IOException(messages.text("library.copyMismatch", source))
                }
                if (before != null) copiedRepository.verifyCurrent()
            }
            for ((source, target) in mapping) {
                Files.move(stage.resolve(source.fileName), target, ATOMIC_MOVE)
                committed.add(target)
            }
            return mapping
        } catch (error: Exception) {
            committed.asReversed().forEach { target ->
                try { Files.move(target, stage.resolve(target.fileName), ATOMIC_MOVE) }
                catch (rollback: Exception) { error.addSuppressed(rollback) }
            }
            throw error
        } finally { deleteTree(stage) }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isDirectory || Files.isSymbolicLink(dir)) throw IOException(messages.text("library.unsupportedEntry", dir))
                Files.createDirectory(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) throw IOException(messages.text("library.unsupportedEntry", file))
                Files.copy(file, target.resolve(source.relativize(file)), NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun compareTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!Files.isDirectory(target.resolve(source.relativize(dir)), NOFOLLOW_LINKS)) {
                    throw IOException(messages.text("library.copyMismatch", dir))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val copied = target.resolve(source.relativize(file))
                if (!attrs.isRegularFile || !Files.isRegularFile(copied, NOFOLLOW_LINKS) || Files.mismatch(file, copied) != -1L) {
                    throw IOException(messages.text("library.copyMismatch", file))
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    fun name(repository: Path): String {
        val label = repository.resolve("name.txt")
        if (!Files.isRegularFile(label, NOFOLLOW_LINKS)) return repository.fileName.toString()
        return runCatching {
            if (Files.size(label) > 1024) return@runCatching repository.fileName.toString()
            Files.newBufferedReader(label).use { it.readLine().orEmpty().take(80) }.ifBlank { repository.fileName.toString() }
        }.getOrDefault(repository.fileName.toString())
    }

    fun name(repository: Path, value: String) {
        require(repository.toAbsolutePath().normalize().parent == directory) { messages.text("library.outside") }
        val label = value.trim().lineSequence().firstOrNull().orEmpty().take(80)
        require(label.isNotBlank()) { messages.text("library.nameRequired") }
        val pending = Files.createTempFile(repository, ".name-", ".tmp")
        try {
            Files.writeString(pending, "$label\n")
            try { Files.move(pending, repository.resolve("name.txt"), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending, repository.resolve("name.txt"), REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(pending) }
    }

    companion object {
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".enderlink", "worlds")
    }
}
