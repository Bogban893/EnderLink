package org.enderlink.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliArgumentsTest {
    @Test
    fun `listen использует порт по умолчанию`() {
        assertEquals(CliOptions("listen", null, 25570), parseArguments(arrayOf("listen")))
    }

    @Test
    fun `connect разбирает адрес и порт в любом порядке`() {
        assertEquals(
            CliOptions("connect", "127.0.0.1", 30000),
            parseArguments(arrayOf("connect", "--port", "30000", "--host", "127.0.0.1")),
        )
    }

    @Test
    fun `неверные аргументы отклоняются до открытия сокета`() {
        val invalid = listOf(
            arrayOf<String>(),
            arrayOf("unknown"),
            arrayOf("connect"),
            arrayOf("connect", "--host", ""),
            arrayOf("listen", "--port"),
            arrayOf("listen", "--port", "--host", "localhost"),
            arrayOf("listen", "--port", "abc"),
            arrayOf("listen", "--port", "0"),
            arrayOf("listen", "--port", "65536"),
            arrayOf("listen", "--unknown", "value"),
            arrayOf("listen", "--port", "1234", "--port", "2345"),
        )
        for (args in invalid) {
            assertFailsWith<IllegalArgumentException>(args.joinToString(" ")) { parseArguments(args) }
        }
    }
}
