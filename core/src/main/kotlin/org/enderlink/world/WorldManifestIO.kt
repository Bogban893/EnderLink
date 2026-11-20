package org.enderlink.world

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Дисковый формат манифеста. Хеши идентификаторов вычисляются отдельно по правилам формата 1. */
internal object WorldManifestIO {
    private const val MAGIC = 0x454c5731 // ELW1
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_BYTES = 256L * 1024 * 1024

    fun write(path: Path, manifest: WorldManifest) {
        manifest.validate()
        DataOutputStream(Files.newOutputStream(path)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(manifest.formatVersion)
            out.writeUTF(manifest.worldId)
            out.writeUTF(manifest.versionId)
            out.writeBoolean(manifest.parentVersionId != null)
            if (manifest.parentVersionId != null) out.writeUTF(manifest.parentVersionId)
            out.writeUTF(manifest.treeHash)
            out.writeInt(manifest.entries.size)
            for (entry in manifest.entries) {
                out.writeUTF(entry.path)
                out.writeByte(if (entry.type == WorldEntryType.DIRECTORY) 0 else 1)
                if (entry.type == WorldEntryType.FILE) {
                    out.writeLong(entry.size)
                    out.writeUTF(entry.hash!!)
                    if (manifest.formatVersion == 2) {
                        out.writeInt(entry.blocks.size)
                        for (block in entry.blocks) { out.writeUTF(block.hash); out.writeInt(block.size) }
                    }
                }
            }
        }
    }

    fun read(path: Path): WorldManifest {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) throw IOException("Некорректный файл манифеста")
        DataInputStream(Files.newInputStream(path, NOFOLLOW_LINKS)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Неизвестный формат манифеста")
            val format = input.readInt()
            val worldId = input.readUTF()
            val versionId = input.readUTF()
            val parent = if (input.readBoolean()) input.readUTF() else null
            val treeHash = input.readUTF()
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Некорректное число записей")
            val entries = ArrayList<WorldEntry>(count)
            repeat(count) {
                val name = input.readUTF()
                entries.add(when (input.readUnsignedByte()) {
                    0 -> WorldEntry(name, WorldEntryType.DIRECTORY)
                    1 -> {
                        val size = input.readLong()
                        val hash = input.readUTF()
                        WorldEntry(name, WorldEntryType.FILE, size, hash, readBlocks(input, format, size))
                    }
                    else -> throw IOException("Неизвестный тип записи")
                })
            }
            if (input.read() != -1) throw IOException("Лишние данные в манифесте")
            return WorldManifest(format, worldId, versionId, parent, treeHash, entries).also { it.validate() }
        }
    }

    internal fun readBlocks(input: DataInputStream, format: Int, size: Long): List<WorldBlock> {
        if (format == 1) return emptyList()
        if (format != 2 || size < 0) throw IOException("Некорректный формат блоков")
        val count = input.readInt()
        validateBlockCount(count, size)
        return List(count) { WorldBlock(input.readUTF(), input.readInt()) }
    }

    internal fun validateBlockCount(count: Int, size: Long) {
        if (size < 0) throw IOException("Некорректный размер файла")
        val expected = size / WorldManifest.BLOCK_SIZE + if (size % WorldManifest.BLOCK_SIZE != 0L) 1 else 0
        if (count < 0 || count.toLong() != expected || count > 2_000_000) throw IOException("Некорректное число блоков")
    }
}
