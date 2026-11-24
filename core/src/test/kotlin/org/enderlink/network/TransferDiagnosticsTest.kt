package org.enderlink.network

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TransferDiagnosticsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `failure record contains operation ID and nested cause with private permissions`() {
        val id = UUID.randomUUID()
        val folder = root.resolve("diagnostics")
        val path = TransferDiagnostics.record(id, IOException("network", IOException("disk full")), folder)
        assertEquals(folder.resolve("$id.log"), path)
        val content = Files.readString(path)
        assertContains(content, "operation=$id")
        assertContains(content, "java.io.IOException: network")
        assertContains(content, "java.io.IOException: disk full")
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(folder))
        }
    }
}
