package org.enderlink.transfer.resume

import org.enderlink.transfer.TransferPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

internal object DirectoryManifest {
    const val HEADER = 14
    const val ENTRY = 15
    data class Entry(val path: String, val directory: Boolean, val size: Long, val hash: ByteArray) {
        fun encode(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeUTF(path); writeBoolean(directory); writeLong(size)
                if (!directory) write(hash)
            }
            return bytes.toByteArray()
        }
    }
    data class Header(val name: String, val total: Long, val count: Int, val hash: ByteArray)
    fun encodeHeader(name: String, total: Long, count: Int, hash: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeUTF(name); writeLong(total); writeInt(count); write(hash) }
        return bytes.toByteArray()
    }
    fun decodeHeader(bytes: ByteArray): Header {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val name = input.readUTF()
        TransferPaths.validateName(name)
        val total = input.readLong()
        val count = input.readInt()
        val hash = ByteArray(32).also(input::readFully)
        if (total < 0 || count < 0 || input.available() != 0) throw IOException("Некорректное описание каталога")
        return Header(name, total, count, hash)
    }
    fun decodeEntry(bytes: ByteArray): Entry {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val path = input.readUTF()
        val directory = input.readBoolean()
        val size = input.readLong()
        val hash = if (directory) ByteArray(0) else ByteArray(32).also(input::readFully)
        if (size < 0 || directory && size != 0L || input.available() != 0) throw IOException("Некорректный элемент каталога")
        return Entry(path, directory, size, hash)
    }
    fun hash(entries: List<Entry>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries) digest.update(entry.encode())
        return digest.digest()
    }
}
