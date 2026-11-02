package org.enderlink.network.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Кодирование сообщения: длина в 4 байтах big-endian, затем содержимое. */
class FrameCodec(private val maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD) {

    init {
        require(maxPayloadSize in 0..Int.MAX_VALUE - 4) {
            "Недопустимый максимальный размер сообщения: $maxPayloadSize"
        }
    }

    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= maxPayloadSize) { "Сообщение превышает допустимый размер" }

        val data = DataOutputStream(output)
        val frame = ByteArray(4 + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        payload.copyInto(frame, destinationOffset = 4)
        data.write(frame)
        data.flush()
    }

    fun read(input: InputStream): ByteArray? {
        val firstByte = input.read()
        if (firstByte == -1) return null

        val data = DataInputStream(input)
        val size =
            (firstByte shl 24) or (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or (data.readUnsignedByte())

        if (size !in 0..maxPayloadSize) {
            throw IOException("Недопустимый размер сообщения при чтении: $size")
        }

        val payload = ByteArray(size)
        data.readFully(payload)
        return payload
    }

    companion object {
        const val DEFAULT_MAX_PAYLOAD = 1024 * 1024
    }
}
