package org.enderlink.worldtransfer

import org.enderlink.network.connection.Connection
import org.enderlink.world.WorldEntry
import org.enderlink.world.WorldEntryType
import org.enderlink.world.WorldManifest
import org.enderlink.world.WorldManifestIO
import org.enderlink.world.WorldBlock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal object WorldTransferProtocol {
    private const val MAGIC = 0x454c5732 // ELW2, отдельно от обычной передачи ELF1
    const val HEADER = 1
    const val ENTRY = 2
    const val READY = 3
    const val SAME = 4
    const val HAVE = 5
    const val NEED = 6
    const val DATA = 7
    const val END = 8
    const val STORED = 9
    const val COMPLETE = 10
    const val ERROR = 11
    const val BLOCK = 12
    const val BLOCK_SIZE = 256 * 1024
    private const val MAX_ENTRIES = 1_000_000

    fun send(connection: Connection, type: Int, body: ByteArray = byteArrayOf()) {
        val bytes = ByteArrayOutputStream(5 + body.size)
        DataOutputStream(bytes).use { out -> out.writeInt(MAGIC); out.writeByte(type); out.write(body) }
        connection.send(bytes.toByteArray())
    }

    fun read(connection: Connection): Pair<Int, ByteArray> {
        val bytes = connection.receive() ?: throw EOFException("Соединение прервано до подтверждения версии мира")
        if (bytes.size < 5) throw IOException("Некорректное сообщение версии мира")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC) throw IOException("Ожидается передача версии мира")
        val type = input.readUnsignedByte()
        val body = input.readBytes()
        if (type == ERROR) throw IOException("Получатель: ${DataInputStream(ByteArrayInputStream(body)).readUTF()}")
        return type to body
    }

    fun expect(connection: Connection, expected: Int) {
        val (type, body) = read(connection)
        if (type != expected || body.isNotEmpty()) throw IOException("Неожиданное сообщение версии мира: $type")
    }

    fun sendError(connection: Connection, error: Throwable) {
        runCatching {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeUTF((error.message ?: "Ошибка передачи мира").take(1000)) }
            send(connection, ERROR, bytes.toByteArray())
        }
    }

    fun sendManifest(connection: Connection, manifest: WorldManifest) {
        manifest.validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(manifest.formatVersion)
            out.writeUTF(manifest.worldId)
            out.writeUTF(manifest.versionId)
            out.writeBoolean(manifest.parentVersionId != null)
            if (manifest.parentVersionId != null) out.writeUTF(manifest.parentVersionId)
            out.writeUTF(manifest.treeHash)
            out.writeInt(manifest.entries.size)
        }
        send(connection, HEADER, bytes.toByteArray())
        for (entry in manifest.entries) {
            val encoded = ByteArrayOutputStream()
            DataOutputStream(encoded).use { out ->
                out.writeUTF(entry.path)
                out.writeByte(if (entry.type == WorldEntryType.DIRECTORY) 0 else 1)
                if (entry.type == WorldEntryType.FILE) {
                    out.writeLong(entry.size); out.writeUTF(entry.hash!!)
                    if (manifest.formatVersion == 2) out.writeInt(entry.blocks.size)
                }
            }
            send(connection, ENTRY, encoded.toByteArray())
            if (manifest.formatVersion == 2 && entry.type == WorldEntryType.FILE) {
                for (block in entry.blocks) {
                    val bytes = ByteArrayOutputStream()
                    DataOutputStream(bytes).use { out -> out.writeUTF(block.hash); out.writeInt(block.size) }
                    send(connection, BLOCK, bytes.toByteArray())
                }
            }
        }
    }

    fun receiveManifest(connection: Connection): WorldManifest {
        val (type, body) = read(connection)
        if (type != HEADER) throw IOException("Ожидается манифест мира")
        val header = DataInputStream(ByteArrayInputStream(body))
        val format = header.readInt()
        val worldId = header.readUTF()
        val versionId = header.readUTF()
        val parent = if (header.readBoolean()) header.readUTF() else null
        val treeHash = header.readUTF()
        val count = header.readInt()
        if (count !in 0..MAX_ENTRIES || header.available() != 0) throw IOException("Некорректный заголовок мира")
        val entries = ArrayList<WorldEntry>(count)
        repeat(count) {
            val (entryType, entryBody) = read(connection)
            if (entryType != ENTRY) throw IOException("Ожидается запись мира")
            val input = DataInputStream(ByteArrayInputStream(entryBody))
            val path = input.readUTF()
            entries.add(when (input.readUnsignedByte()) {
                0 -> WorldEntry(path, WorldEntryType.DIRECTORY)
                1 -> {
                    val size = input.readLong()
                    val hash = input.readUTF()
                    if (format == 2) {
                        val count = input.readInt()
                        WorldManifestIO.validateBlockCount(count, size)
                        if (input.available() != 0) throw IOException("Лишние данные записи мира")
                        val blocks = List(count) {
                            val (blockType, blockBody) = read(connection)
                            if (blockType != BLOCK) throw IOException("Ожидается описание блока")
                            val blockInput = DataInputStream(ByteArrayInputStream(blockBody))
                            val block = WorldBlock(blockInput.readUTF(), blockInput.readInt())
                            if (blockInput.available() != 0) throw IOException("Лишние данные блока")
                            block
                        }
                        WorldEntry(path, WorldEntryType.FILE, size, hash, blocks)
                    } else WorldEntry(path, WorldEntryType.FILE, size, hash)
                }
                else -> throw IOException("Неизвестный тип записи мира")
            })
            if (input.available() != 0) throw IOException("Лишние данные записи мира")
        }
        return WorldManifest(format, worldId, versionId, parent, treeHash, entries).also { it.validate() }
    }

    /** Превращает последовательность DATA...END в поток без хранения целого файла в памяти. */
    class ObjectInput(private val connection: Connection) : InputStream() {
        private var block = byteArrayOf()
        private var offset = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            while (offset == block.size) {
                if (ended) return -1
                val (type, body) = WorldTransferProtocol.read(connection)
                when (type) {
                    DATA -> {
                        if (body.isEmpty() || body.size > BLOCK_SIZE) throw IOException("Некорректный блок мира")
                        block = body
                        offset = 0
                    }
                    END -> {
                        if (body.isNotEmpty()) throw IOException("Некорректное завершение файла")
                        ended = true
                        return -1
                    }
                    else -> throw IOException("Ожидаются данные файла мира")
                }
            }
            val count = minOf(len, block.size - offset)
            block.copyInto(bytes, off, offset, offset + count)
            offset += count
            return count
        }
    }
}
