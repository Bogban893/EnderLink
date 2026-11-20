package org.enderlink.cli.command

import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransferRetryTest {
    @Test
    fun `повторяет только обрыв и останавливается после лимита`() {
        var attempts = 0
        val result = retryTransfer(2, receiving = true) {
            attempts++
            if (attempts < 3) throw EOFException("обрыв")
            42
        }
        assertEquals(42, result)
        assertEquals(3, attempts)

        attempts = 0
        val failure = assertFailsWith<IOException> {
            retryTransfer(1, receiving = true) {
                attempts++
                throw EOFException("обрыв")
            }
        }
        assertEquals(2, attempts)
        assertTrue(failure.message!!.contains("Исчерпано попыток"))
    }

    @Test
    fun `ошибка проверки не повторяется`() {
        var attempts = 0
        assertFailsWith<IOException> {
            retryTransfer(2, receiving = true) {
                attempts++
                throw IOException("SHA-256 не совпадает")
            }
        }
        assertEquals(1, attempts)
    }
}
