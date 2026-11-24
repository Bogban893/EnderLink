package org.enderlink.network

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/** Stores a short local failure record by operation ID. Callers keep reporting the original error if logging fails. */
object TransferDiagnostics {
    fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".enderlink", "diagnostics")

    fun record(id: UUID, failure: Throwable, directory: Path = defaultDirectory()): Path {
        if (Files.isSymbolicLink(directory)) throw IOException("Каталог диагностики не должен быть ссылкой")
        Files.createDirectories(directory)
        if (Files.isSymbolicLink(directory)) throw IOException("Каталог диагностики не должен быть ссылкой")
        val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
        if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val path = directory.resolve("$id.log")
        if (posix) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else Files.createFile(path)
        val causes = generateSequence(failure) { it.cause }.take(8).joinToString("\n") { cause ->
            "${cause.javaClass.name}: ${cause.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(2000)}"
        }
        Files.writeString(path, "${Instant.now()} operation=$id\n$causes\n", WRITE)
        return path
    }
}
