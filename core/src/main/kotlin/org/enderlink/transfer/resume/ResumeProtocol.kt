package org.enderlink.transfer.resume

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

internal object ResumeProtocol {
    const val HEADER = 10
    const val BLOCK = 11
    const val HAVE = 12
    const val NEED = 13
    const val BLOCK_SIZE = 256 * 1024
    data class Header(val name: String, val size: Long, val hash: ByteArray)
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun header(name: String, size: Long, hash: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeUTF(name); writeLong(size); write(hash) }
        return bytes.toByteArray()
    }
    fun parseHeader(bytes: ByteArray): Header {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val name = input.readUTF()
        val size = input.readLong()
        val hash = ByteArray(32).also(input::readFully)
        if (size < 0 || input.available() != 0) throw IOException("Некорректный заголовок докачки")
        return Header(name, size, hash)
    }
}
