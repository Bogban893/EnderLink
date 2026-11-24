package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal enum class DirectoryPurpose(val key: String) {
    REPOSITORY("repository"), SOURCE("source"), SAVES("saves"), SERVER("server"), LIBRARY("library"),
}

/** Checks a chooser result before replacing a desktop field. Operations repeat their own safety checks. */
internal object DesktopPathSelection {
    fun directory(purpose: DirectoryPurpose, selected: Path, library: Path?, saves: Path?, repository: Path?) {
        if (!Files.isDirectory(selected, NOFOLLOW_LINKS)) throw IOException(messages.text("fileChooser.directoryRequired"))
        if (!Files.isReadable(selected) || purpose != DirectoryPurpose.SOURCE && !Files.isWritable(selected)) {
            throw IOException(messages.text("fileChooser.directoryAccess"))
        }
        if (purpose == DirectoryPurpose.SOURCE) {
            val level = selected.resolve("level.dat")
            if (!Files.isRegularFile(level, NOFOLLOW_LINKS) || !Files.isReadable(level)) {
                throw IOException(messages.text("transfer.source.required"))
            }
            if (overlaps(selected, library) || overlaps(selected, repository)) {
                throw IOException(messages.text("repository.sourceOverlap"))
            }
        }
        if (purpose != DirectoryPurpose.SOURCE && purpose != DirectoryPurpose.SERVER &&
            Files.exists(selected.resolve("level.dat"), NOFOLLOW_LINKS)) {
            throw IOException(messages.text("fileChooser.worldNotContainer"))
        }
        if (purpose == DirectoryPurpose.SAVES && (overlaps(selected, library) || overlaps(selected, repository)) ||
            purpose == DirectoryPurpose.LIBRARY && overlaps(selected, saves) ||
            purpose == DirectoryPurpose.REPOSITORY && overlaps(selected, saves)) {
            throw IOException(messages.text("library.overlap"))
        }
    }

    fun jar(selected: Path) {
        if (!Files.isRegularFile(selected, NOFOLLOW_LINKS) || !Files.isReadable(selected) ||
            !selected.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            throw IOException(messages.text("fileChooser.jarRequired"))
        }
    }

    private fun overlaps(left: Path, right: Path?): Boolean {
        if (right == null) return false
        val a = effectivePath(left)
        val b = effectivePath(right)
        return a.startsWith(b) || b.startsWith(a)
    }

    private fun effectivePath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent ?: return absolute
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
    }
}
