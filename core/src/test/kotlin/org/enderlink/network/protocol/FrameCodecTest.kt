package org.enderlink.network.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameCodecTest {
    private val codec = FrameCodec()

    private fun encode(vararg payloads: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        payloads.forEach { codec.write(out, it) }
        return out.toByteArray()
    }

    /** Отдаёт данные по одному байту, как медленный сокет. */
    private class OneByteInputStream(private val src: InputStream) : InputStream() {
        override fun read(): Int = src.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val v = src.read()
            if (v == -1) return -1
            b[off] = v.toByte()
            return 1
        }
    }

    @Test
    fun `обычное сообщение`() {
        val msg = "привет".toByteArray()
        val input = ByteArrayInputStream(encode(msg))
        assertContentEquals(msg, codec.read(input))
        assertNull(codec.read(input))
    }

    @Test
    fun `пустое сообщение отличается от конца потока`() {
        val input = ByteArrayInputStream(encode(ByteArray(0)))
        assertContentEquals(ByteArray(0), codec.read(input))
        assertNull(codec.read(input))
    }

    @Test
    fun `несколько сообщений подряд`() {
        val a = byteArrayOf(1, 2, 3)
        val b = ByteArray(0)
        val c = byteArrayOf(9)
        val input = ByteArrayInputStream(encode(a, b, c))
        assertContentEquals(a, codec.read(input))
        assertContentEquals(b, codec.read(input))
        assertContentEquals(c, codec.read(input))
        assertNull(codec.read(input))
    }

    @Test
    fun `чтение частями`() {
        val msg = ByteArray(300) { it.toByte() }
        val input = OneByteInputStream(ByteArrayInputStream(encode(msg, msg)))
        assertContentEquals(msg, codec.read(input))
        assertContentEquals(msg, codec.read(input))
    }

    @Test
    fun `обрыв внутри длины`() {
        val bytes = encode(byteArrayOf(1, 2, 3)).copyOf(2)
        assertFailsWith<EOFException> { codec.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `обрыв внутри содержимого`() {
        val bytes = encode(byteArrayOf(1, 2, 3, 4, 5)).copyOf(4 + 2)
        assertFailsWith<EOFException> { codec.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `запись слишком большого сообщения`() {
        val small = FrameCodec(maxPayloadSize = 8)
        assertFailsWith<IllegalArgumentException> {
            small.write(ByteArrayOutputStream(), ByteArray(9))
        }
        small.write(ByteArrayOutputStream(), ByteArray(8)) // граница допустима
    }

    @Test
    fun `чтение сообщения предельного размера`() {
        val small = FrameCodec(maxPayloadSize = 8)
        val msg = ByteArray(8) { it.toByte() }
        val output = ByteArrayOutputStream()
        small.write(output, msg)
        assertContentEquals(msg, small.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `отрицательный лимит отклоняется`() {
        assertFailsWith<IllegalArgumentException> { FrameCodec(maxPayloadSize = -1) }
    }

    @Test
    fun `чтение недопустимой длины`() {
        val small = FrameCodec(maxPayloadSize = 8)
        val tooBig = byteArrayOf(0, 0, 0, 9) + ByteArray(9)
        val negative = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<IOException> { small.read(ByteArrayInputStream(tooBig)) }
        assertFailsWith<IOException> { small.read(ByteArrayInputStream(negative)) }
    }

    @Test
    fun `формат длины - 4 байта big-endian`() {
        val bytes = encode(byteArrayOf(7, 7))
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 7, 7), bytes)
    }
}
