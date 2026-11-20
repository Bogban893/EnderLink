package org.enderlink.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliArgumentsTest {
    @Test
    fun `версии мира используют отдельные команды`() {
        assertEquals("repo", parseArguments(arrayOf("world-publish", "--source", "world", "--repository", "repo")).repository)
        assertEquals("world", parseArguments(arrayOf("world-publish", "--source", "world", "--repository", "repo")).source)
        assertEquals(true, parseArguments(arrayOf("world-receive", "--repository", "repo", "--iroh")).iroh)
        assertEquals("friend", parseArguments(arrayOf("world-send", "--repository", "repo", "--peer", "friend")).peer)
        assertEquals("sha256:id", parseArguments(arrayOf("world-checkout", "--repository", "repo", "--version", "sha256:id")).version)
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("world-send", "--repository", "repo")) }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("world-receive", "--output", "out")) }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("world-publish", "--repository", "repo", "--host", "localhost")) }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("world-checkout", "--repository", "repo")) }
    }
    @Test
    fun `iroh выбирается по другу а TCP параметры не смешиваются с ним`() {
        val send = parseArguments(arrayOf("send", "--peer", "friend", "--file", "world.zip", "--relay", "https://example.org/", "--profile", "profile"))
        assertEquals(true, send.iroh)
        assertEquals("friend", send.peer)
        assertEquals(null, send.host)
        assertEquals("world.zip", send.file)
        assertEquals(true, parseArguments(arrayOf("receive", "--iroh", "--output", "out")).iroh)
        for (args in listOf(
            arrayOf("send", "--peer", "friend", "--host", "localhost", "--file", "world.zip"),
            arrayOf("listen", "--iroh", "--port", "25570"),
            arrayOf("connect", "--iroh"),
            arrayOf("connect", "--host", "localhost", "--relay", "https://example.org/"),
            arrayOf("receive", "--iroh", "--output", "out", "--iroh"),
        )) assertFailsWith<IllegalArgumentException> { parseArguments(args) }
    }

    @Test
    fun `команды профиля требуют явные имя и ID`() {
        val add = parseArguments(arrayOf("add", "friend", "public-id", "--profile", "profile"))
        assertEquals("friend", add.friendName)
        assertEquals("public-id", add.friendId)
        assertEquals("profile", add.profile)
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("add", "friend")) }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("remove", "--profile", "profile")) }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("id", "--host", "localhost")) }
    }

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
    fun `команды файлов принимают свои параметры`() {
        assertEquals(
            CliOptions("send", "localhost", 25570, file = "my world.zip"),
            parseArguments(arrayOf("send", "--host", "localhost", "--file", "my world.zip")),
        )
        assertEquals(
            CliOptions("receive", null, 25570, output = "received"),
            parseArguments(arrayOf("receive", "--output", "received")),
        )
        assertEquals(5, parseArguments(arrayOf("receive", "--output", "received", "--retries", "5")).retries)
        assertFailsWith<IllegalArgumentException> {
            parseArguments(arrayOf("send", "--host", "localhost", "--file", "source", "--retries", "6"))
        }
    }

    @Test
    fun `каталог выбирается отдельным параметром`() {
        assertEquals(
            CliOptions("send", "localhost", 25570, directory = "Мой мир"),
            parseArguments(arrayOf("send", "--host", "localhost", "--directory", "Мой мир")),
        )
        assertFailsWith<IllegalArgumentException> {
            parseArguments(arrayOf("send", "--host", "localhost", "--file", "a", "--directory", "b"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseArguments(arrayOf("send", "--host", "localhost", "--directory", ""))
        }
    }

    @Test
    fun `неверные аргументы отклоняются до открытия сокета`() {
        val invalid = listOf(
            arrayOf<String>(),
            arrayOf("unknown"),
            arrayOf("connect"),
            arrayOf("send", "--host", "localhost"),
            arrayOf("send", "--file", "file.bin"),
            arrayOf("receive"),
            arrayOf("receive", "--output", ""),
            arrayOf("listen", "--file", "file.bin"),
            arrayOf("send", "--host", "localhost", "--file", "file.bin", "--output", "received"),
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
