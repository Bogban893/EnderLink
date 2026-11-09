package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.TransferPaths
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class ResumableDirectoryReceiver {
    internal fun receiveHeader(
        connection: Connection, directory: Path, body: ByteArray,
        progress: (Long, Long) -> Unit,
    ): Path {
        val header = DirectoryManifest.decodeHeader(body)
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        val target = TransferPaths.resolve(root, header.name)
        FileChannel.open(root.resolve(".enderlink-directory.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
                ?: throw IOException("В этом каталоге уже принимается дерево")
            lock.use {
                Protocol.send(connection, Protocol.READY)
                val entries = mutableListOf<DirectoryManifest.Entry>()
                val known = mutableMapOf<String, Boolean>()
                var total = 0L
                repeat(header.count) {
                    val (type, bytes) = Protocol.read(connection)
                    if (type != DirectoryManifest.ENTRY) throw IOException("Ожидается элемент манифеста")
                    val entry = DirectoryManifest.decodeEntry(bytes)
                    val path = TransferPaths.resolve(root, entry.path)
                    val parent = root.relativize(path.parent).joinToString("/") { it.toString() }
                    if (parent.isNotEmpty() && known[parent] != true) throw IOException("Отсутствует родительский каталог")
                    if (known.putIfAbsent(entry.path, entry.directory) != null) throw IOException("Повторный путь")
                    if (entry.size > header.total - total) throw IOException("Объём манифеста не совпадает")
                    total += entry.size
                    entries.add(entry)
                }
                if (total != header.total || !MessageDigest.isEqual(DirectoryManifest.hash(entries), header.hash)) {
                    throw IOException("Манифест каталога повреждён")
                }
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(target, NOFOLLOW_LINKS)) throw IOException("Каталог уже существует и отличается: $target")
                    verifyTree(target, entries, connection)
                    progress(total, total)
                    Protocol.send(connection, ResumeProtocol.COMPLETE)
                    return target
                }
                val cache = root.resolve(".enderlink-directory-${ResumeProtocol.hex(ResumeProtocol.digest(body))}")
                ensureDirectory(cache)
                val tree = cache.resolve("tree")
                val states = cache.resolve("files")
                ensureDirectory(tree)
                ensureDirectory(states)
                for (entry in entries.filter { it.directory }) ensureDirectory(TransferPaths.resolve(tree, entry.path))
                Protocol.send(connection, Protocol.READY)
                var done = 0L
                progress(0, total)
                for (entry in entries.filter { !it.directory }) {
                    val (type, encoded) = Protocol.read(connection)
                    if (type != Protocol.FILE_ENTRY || !encoded.contentEquals(entry.encode())) throw IOException("Неожиданный файл каталога")
                    val path = TransferPaths.resolve(tree, entry.path)
                    if (matches(path, entry, connection)) {
                        Protocol.send(connection, ResumeProtocol.HAVE)
                    } else {
                        if (Files.exists(path, NOFOLLOW_LINKS)) {
                            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Некорректный сохранённый файл")
                            Files.delete(path)
                        }
                        Protocol.send(connection, ResumeProtocol.NEED)
                        val (fileType, fileBody) = Protocol.read(connection)
                        if (fileType != ResumeProtocol.HEADER) throw IOException("Ожидается файл с докачкой")
                        val file = ResumeProtocol.parseHeader(fileBody)
                        if (file.name != path.fileName.toString() || file.size != entry.size ||
                            !MessageDigest.isEqual(file.hash, entry.hash)) throw IOException("Файл не соответствует манифесту")
                        val state = states.resolve(ResumeProtocol.hex(ResumeProtocol.digest(entry.path.toByteArray(Charsets.UTF_8))))
                        ensureDirectory(state)
                        ResumableFileReceiver().receiveHeader(connection, path.parent, fileBody,
                            { current, _ -> progress(done + current, total) }, state)
                    }
                    done += entry.size
                    progress(done, total)
                }
                Protocol.expect(connection, Protocol.DIRECTORY_END)
                // Проверяем также лишние файлы и изменения состояния на диске.
                verifyTree(tree, entries, connection)
                Files.move(tree, target)
                deleteState(cache)
                Protocol.send(connection, Protocol.SUCCESS)
                return target
            }
        }
    }

    private fun verifyTree(tree: Path, entries: List<DirectoryManifest.Entry>, connection: Connection) {
        val expected = entries.associateBy { it.path }
        var seen = 0
        Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != tree) {
                    val key = tree.relativize(dir).joinToString("/") { it.toString() }
                    if (expected[key]?.directory != true) throw IOException("Неожиданный каталог в сохранённом дереве")
                    seen++
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val key = tree.relativize(file).joinToString("/") { it.toString() }
                val entry = expected[key] ?: throw IOException("Неожиданный файл в сохранённом дереве")
                if (entry.directory || !matches(file, entry, connection)) throw IOException("Сохранённый файл повреждён")
                seen++
                return FileVisitResult.CONTINUE
            }
        })
        if (seen != entries.size) throw IOException("Состав каталога не совпадает")
    }

    private fun ensureDirectory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw IOException("Ожидается обычный каталог: $path")
    }

    private fun matches(path: Path, entry: DirectoryManifest.Entry, connection: Connection): Boolean {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != entry.size) return false
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(ResumeProtocol.BLOCK_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                hash.update(buffer, 0, count)
                Protocol.send(connection, ResumeProtocol.VERIFYING)
            }
        }
        return MessageDigest.isEqual(hash.digest(), entry.hash)
    }

    private fun deleteState(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
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
}
