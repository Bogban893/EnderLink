package org.enderlink.transfer.protocol

import org.enderlink.network.connection.Connection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Файлы и каталоги на одном соединении; каждый файл завершается проверкой и подтверждением. */
internal object TransferProtocol {
    const val BLOCK_SIZE = 256 * 1024
    const val HEADER = 1
    const val DATA = 2
    const val END = 3
    const val READY = 4
    const val SUCCESS = 5
    const val DIRECTORY_HEADER = 6
    const val DIRECTORY_ENTRY = 7
    const val FILE_ENTRY = 8
    const val DIRECTORY_END = 9
    private const val MAGIC = 0x454C4631 // ELF1

    fun send(connection: Connection, type: Int, body: ByteArray = ByteArray(0)) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(MAGIC)
            writeByte(type)
            write(body)
        }
        connection.send(bytes.toByteArray())
    }

    fun read(connection: Connection): Pair<Int, ByteArray> {
        val bytes = connection.receive() ?: throw EOFException("Передача прервана до подтверждения")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC) throw IOException("Ожидается режим передачи файла")
        val type = input.readUnsignedByte()
        return type to input.readBytes()
    }

    fun expect(connection: Connection, type: Int) {
        val (actual, body) = read(connection)
        if (actual != type || body.isNotEmpty()) throw IOException("Неожиданный ответ при передаче файла")
    }

    fun header(name: String, size: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeUTF(name); writeLong(size) }
        return bytes.toByteArray()
    }

    fun directoryHeader(name: String, size: Long, entries: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.write(header(name, size))
        DataOutputStream(bytes).writeInt(entries)
        return bytes.toByteArray()
    }

    fun parseDirectoryHeader(body: ByteArray): Triple<String, Long, Int> {
        if (body.size < 4) throw IOException("Некорректное описание каталога")
        val (name, size) = parseHeader(body.copyOfRange(0, body.size - 4))
        val count = DataInputStream(ByteArrayInputStream(body, body.size - 4, 4)).readInt()
        if (count < 0) throw IOException("Некорректное число элементов каталога")
        return Triple(name, size, count)
    }

    fun parseHeader(body: ByteArray): Pair<String, Long> {
        val input = DataInputStream(ByteArrayInputStream(body))
        val name = input.readUTF()
        val size = input.readLong()
        if (size < 0 || input.available() != 0) throw IOException("Некорректное описание файла")
        return name to size
    }
}
