package org.enderlink.transfer.resume

import org.enderlink.network.connection.Connection
import org.enderlink.transfer.TransferPaths
import org.enderlink.transfer.protocol.TransferProtocol as Protocol
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Сканирование и хеширование выполняются до подключения. Источник должен быть закрыт для изменений. */
class ResumableDirectorySender(source: Path) {
    private val root = source.toAbsolutePath().normalize()
    private val files = mutableMapOf<String, ResumableFileSender>()
    private val entries = mutableListOf<DirectoryManifest.Entry>()
    private val header: ByteArray
    init {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Не найден обычный каталог: $source")
        val name = root.fileName?.toString() ?: throw IOException("Нельзя отправлять корень файловой системы")
        TransferPaths.validateName(name)
        fun relative(path: Path) = root.relativize(path).joinToString("/") { it.toString() }.also { TransferPaths.resolve(root, it) }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) entries.add(DirectoryManifest.Entry(relative(dir), true, 0, ByteArray(0)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) throw IOException("Ссылки и специальные файлы не поддерживаются: $file")
                val path = relative(file)
                val sender = ResumableFileSender(file)
                files[path] = sender
                entries.add(DirectoryManifest.Entry(path, false, sender.size, sender.hash))
                return FileVisitResult.CONTINUE
            }
        })
        // Одинаковое дерево получает один идентификатор независимо от порядка обхода диска.
        entries.sortBy { it.path }
        var total = 0L
        for (entry in entries) {
            if (entry.size > Long.MAX_VALUE - total) throw IOException("Слишком большой каталог")
            total += entry.size
        }
        header = DirectoryManifest.encodeHeader(name, total, entries.size, DirectoryManifest.hash(entries))
    }
    fun send(connection: Connection, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val total = DirectoryManifest.decodeHeader(header).total
        Protocol.send(connection, DirectoryManifest.HEADER, header)
        Protocol.expect(connection, Protocol.READY)
        for (entry in entries) Protocol.send(connection, DirectoryManifest.ENTRY, entry.encode())
        Protocol.expect(connection, Protocol.READY)
        var done = 0L
        progress(0, total)
        for (entry in entries.filter { !it.directory }) {
            Protocol.send(connection, Protocol.FILE_ENTRY, entry.encode())
            val (answer, body) = ResumeProtocol.response(connection)
            if (body.isNotEmpty() || answer !in setOf(ResumeProtocol.HAVE, ResumeProtocol.NEED)) throw IOException("Некорректный запрос файла")
            if (answer == ResumeProtocol.NEED) {
                requireNotNull(files[entry.path]).send(connection) { current, _ -> progress(done + current, total) }
            }
            done += entry.size
            progress(done, total)
        }
        Protocol.send(connection, Protocol.DIRECTORY_END)
        ResumeProtocol.expectSuccess(connection)
    }
}
