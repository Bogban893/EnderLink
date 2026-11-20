package org.enderlink.transfer

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object TransferPaths {
    fun validateName(name: String) {
        if (name.isBlank() || name in setOf(".", "..") || name.any {
                it == '/' || it == '\\' || it == ':' || it.isISOControl()
            }) throw IOException("Недопустимое имя: $name")
    }

    /** В протоколе относительные пути всегда разделяются '/'. */
    fun resolve(root: Path, relative: String): Path {
        val parts = relative.split('/')
        parts.forEach(::validateName)
        try {
            return parts.fold(root) { path, name -> path.resolve(name) }.normalize().also {
                if (!it.startsWith(root)) throw IOException("Путь выходит за пределы каталога")
            }
        } catch (e: InvalidPathException) {
            throw IOException("Недопустимый путь", e)
        }
    }
}
