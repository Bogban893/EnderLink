package org.enderlink.network.tcp

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TcpConnectionTest {
    @Test
    fun `ошибка записи закрывает сокет и сохраняет исходное исключение`() {
        val writeFailure = IOException("write failed")
        val closeFailure = IOException("close failed")
        var closed = false
        val socket = object : Socket() {
            override fun setTcpNoDelay(on: Boolean) = Unit
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) { throw writeFailure }
            }
            override fun close() {
                closed = true
                throw closeFailure
            }
        }
        val connection = TcpConnection.fromSocket(socket)
        val failure = assertFailsWith<IOException> { connection.send(byteArrayOf(1)) }
        assertTrue(closed)
        assertSame(writeFailure, failure)
        assertEquals(listOf(closeFailure), failure.suppressed.toList())
    }

    @Test
    fun `ошибка создания соединения закрывает переданный сокет`() {
        val setupFailure = IOException("input stream failed")
        var closed = false
        val socket = object : Socket() {
            override fun setTcpNoDelay(on: Boolean) = Unit
            override fun getInputStream(): ByteArrayInputStream = throw setupFailure
            override fun close() { closed = true }
        }
        val failure = assertFailsWith<IOException> { TcpConnection.fromSocket(socket) }
        assertTrue(closed)
        assertSame(setupFailure, failure)
    }
}
