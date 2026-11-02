package org.enderlink.transfer

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

class DirectorySender {
    private data class Entry(val path: Path, val relative: String, val directory: Boolean, val size: Long)

    /** Каталог должен оставаться неизменным; символические ссылки и специальные файлы запрещены. */
    fun send(connection: Connection, source: Path, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val root = source.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Не найден обычный каталог: $source")
        val name = root.fileName?.toString() ?: throw IOException("Нельзя отправлять корень файловой системы")
        TransferPaths.validateName(name)
        val entries = mutableListOf<Entry>()
        var total = 0L
        fun relative(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }.also {
            TransferPaths.resolve(root, it)
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) entries.add(Entry(dir, relative(dir), true, 0))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) throw IOException("Ссылки и специальные файлы не поддерживаются: $file")
                if (attrs.size() > Long.MAX_VALUE - total) throw IOException("Слишком большой каталог")
                total += attrs.size()
                entries.add(Entry(file, relative(file), false, attrs.size()))
                return FileVisitResult.CONTINUE
            }
        })
        Protocol.send(connection, Protocol.DIRECTORY_HEADER, Protocol.directoryHeader(name, total, entries.size))
        Protocol.expect(connection, Protocol.READY)
        var sent = 0L
        progress(0, total)
        for (entry in entries) {
            if (entry.directory) {
                if (!Files.isDirectory(entry.path, NOFOLLOW_LINKS)) throw IOException("Исходный каталог изменился")
                Protocol.send(connection, Protocol.DIRECTORY_ENTRY, Protocol.header(entry.relative, 0))
                Protocol.expect(connection, Protocol.READY)
            } else {
                if (!Files.isRegularFile(entry.path, NOFOLLOW_LINKS) || Files.size(entry.path) != entry.size) {
                    throw IOException("Исходный файл изменился: ${entry.path}")
                }
                Protocol.send(connection, Protocol.FILE_ENTRY, Protocol.header(entry.relative, entry.size))
                Protocol.expect(connection, Protocol.READY)
                FileSender().send(connection, entry.path) { done, size ->
                    if (size != entry.size) throw IOException("Размер исходного файла изменился")
                    progress(sent + done, total)
                }
                sent += entry.size
            }
        }
        Protocol.send(connection, Protocol.DIRECTORY_END)
        Protocol.expect(connection, Protocol.SUCCESS)
    }
}
