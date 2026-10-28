package org.enderlink.network.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Кодирование длины и содержимого сообщения. Реализация будет добавлена позже. */
class FrameCodec {
    private val maxPayloadSize = 1024 * 1024

    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= maxPayloadSize) {
            "Сообщение превышает допустимый размер"
        }

        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    fun read(input: InputStream): ByteArray? {
        val firstByte = input.read()
        if (firstByte == -1) return null

        val data = DataInputStream(input)
        val size = try {
            (firstByte shl 24) or
                    (data.readUnsignedByte() shl 16) or
                    (data.readUnsignedByte() shl 8) or
                    data.readUnsignedByte()
        } catch (e: EOFException) {
            throw EOFException("Соединение закрыто внутри длины сообщения: " + e.message)
        }

        require(size in 0..maxPayloadSize) {
            "Недопустимый размер сообщения: $size"
        }

        val payload = ByteArray(size)
        data.readFully(payload)
        return payload
    }
}
