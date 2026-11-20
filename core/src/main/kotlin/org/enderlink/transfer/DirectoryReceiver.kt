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

class DirectoryReceiver {
    fun receive(connection: Connection, directory: Path, progress: (Long, Long) -> Unit = { _, _ -> }): Path =
        receiveHeader(connection, directory, Protocol.read(connection), progress)

    internal fun receiveHeader(
        connection: Connection, directory: Path, header: Pair<Int, ByteArray>,
        progress: (Long, Long) -> Unit,
    ): Path {
        if (header.first != Protocol.DIRECTORY_HEADER) throw IOException("Ожидается описание каталога")
        val (name, total, count) = Protocol.parseDirectoryHeader(header.second)
        TransferPaths.validateName(name)
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        val target = TransferPaths.resolve(root, name)
        if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Каталог уже существует: $target")
        val temporary = Files.createTempDirectory(root, ".enderlink-")
        try {
            Protocol.send(connection, Protocol.READY)
            var received = 0L
            progress(0, total)
            repeat(count) {
                val (type, body) = Protocol.read(connection)
                if (type != Protocol.DIRECTORY_ENTRY && type != Protocol.FILE_ENTRY) {
                    throw IOException("Ожидается элемент каталога")
                }
                val (relative, size) = Protocol.parseHeader(body)
                val path = TransferPaths.resolve(temporary, relative)
                if (!Files.isDirectory(path.parent, NOFOLLOW_LINKS)) throw IOException("Отсутствует родительский каталог")
                if (Files.exists(path, NOFOLLOW_LINKS)) throw IOException("Повторный путь: $relative")
                if (type == Protocol.DIRECTORY_ENTRY) {
                    if (size != 0L) throw IOException("Некорректный размер папки")
                    Files.createDirectory(path)
                    Protocol.send(connection, Protocol.READY)
                } else {
                    if (size > total - received) throw IOException("Размер превышает общий объём каталога")
                    Protocol.send(connection, Protocol.READY)
                    val fileHeader = Protocol.read(connection)
                    if (fileHeader.first != Protocol.HEADER) throw IOException("Ожидается файл")
                    val (fileName, fileSize) = Protocol.parseHeader(fileHeader.second)
                    if (fileName != path.fileName.toString() || fileSize != size) {
                        throw IOException("Описание файла не соответствует элементу каталога")
                    }
                    FileReceiver().receiveHeader(connection, path.parent, fileHeader) { done, _ ->
                        progress(received + done, total)
                    }
                    received += size
                }
            }
            Protocol.expect(connection, Protocol.DIRECTORY_END)
            if (received != total) throw IOException("Общий размер каталога не совпадает")
            Files.move(temporary, target)
            Protocol.send(connection, Protocol.SUCCESS)
            return target
        } finally {
            // Обход не следует по ссылкам. Удаляем только временное дерево этой передачи.
            if (Files.exists(temporary, NOFOLLOW_LINKS)) {
                Files.walkFileTree(temporary, object : SimpleFileVisitor<Path>() {
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
    }
}
