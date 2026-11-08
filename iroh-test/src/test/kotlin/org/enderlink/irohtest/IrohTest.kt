package org.enderlink.irohtest

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IrohTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `ID и друзья сохраняются после перезапуска а ключ не перезаписывается`() {
        val friend = Profile.open(directory.resolve("friend")).use { it.id }
        val own = Profile.open(directory.resolve("own")).use {
            it.add("Друг", friend)
            assertFailsWith<java.io.IOException> { Profile.open(directory.resolve("own")) }
            it.id
        }
        Profile.open(directory.resolve("own")).use {
            assertEquals(own, it.id)
            assertEquals(friend, it.friendId("Друг"))
            assertFailsWith<IllegalArgumentException> { it.add("self", own) }
            it.remove("Друг")
        }
        Profile.open(directory.resolve("own")).use { assertTrue(it.friends().isEmpty()) }
        val keyPath = directory.resolve("own/secret.key")
        Files.write(keyPath, byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { Profile.open(directory.resolve("own")) }
        assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(keyPath))
    }

    @Test
    fun `неизвестный ID отклоняется а друг передает сообщения в обе стороны`() = runBlocking {
        withTimeout(30_000) {
            Profile.open(directory.resolve("server")).use { serverProfile ->
                Profile.open(directory.resolve("client")).use { clientProfile ->
                    serverProfile.add("client", clientProfile.id)
                    clientProfile.add("server", serverProfile.id)
                    val server = Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = serverProfile.key, alpns = listOf(ALPN)))
                    val client = Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = clientProfile.key))
                    val stranger = Endpoint.bind(EndpointOptions(preset = presetMinimal()))
                    try {
                        val logs = mutableListOf<String>()
                        val accepted = async { acceptFriend(server, serverProfile) { logs.add(it) } }
                        stranger.connect(server.addr(), ALPN).use { unknown ->
                            assertNotNull(withTimeout(10_000) { unknown.closed() })
                        }
                        val outgoing = client.connect(server.addr(), ALPN)
                        outgoing.use { connection ->
                            accepted.await().use { incoming ->
                                val opening = async { IrohChat.open(incoming, false) }
                                IrohChat.open(connection, true).use { clientChat ->
                                    opening.await().use { serverChat ->
                                        val data = ByteArray(IrohChat.MAX_MESSAGE) { (it % 251).toByte() }
                                        clientChat.send(data)
                                        assertContentEquals(data, serverChat.receive())
                                        serverChat.send("Привет 🌍".toByteArray())
                                        assertContentEquals("Привет 🌍".toByteArray(), clientChat.receive())
                                        clientChat.send(byteArrayOf())
                                        assertContentEquals(byteArrayOf(), serverChat.receive())
                                        assertFailsWith<IllegalArgumentException> { clientChat.send(ByteArray(IrohChat.MAX_MESSAGE + 1)) }
                                    }
                                }
                                connection.close(0L, "done".toByteArray())
                            }
                        }
                        assertTrue(logs.any { it.startsWith("Отклонён неизвестный ID") })
                    } finally {
                        for (endpoint in listOf(stranger, client, server)) {
                            try { endpoint.shutdown() } finally { endpoint.close() }
                        }
                    }
                }
            }
        }
    }
}
