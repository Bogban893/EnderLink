package org.enderlink.coordinator

import org.enderlink.network.discovery.DiscoveryClient
import org.enderlink.network.discovery.DiscoveryProtocol
import org.enderlink.network.discovery.DiscoveryRequest
import org.enderlink.network.discovery.Presence
import org.enderlink.network.iroh.IrohProfile
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinatorServiceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `only signed allowed friends see fresh addresses and readiness`() {
        val now = AtomicLong(1_000_000)
        CoordinatorService.open(now = now::get).use { service ->
            service.start()
            val client = DiscoveryClient(URI("http://127.0.0.1:${service.port}/"))
            IrohProfile.open(root.resolve("A")).use { a ->
                IrohProfile.open(root.resolve("B")).use { b ->
                    IrohProfile.open(root.resolve("C")).use { c ->
                        a.add("B", b.id)
                        b.add("A", a.id)
                        client.register(a, "https://relay.example", listOf("127.0.0.1:12345"), true)
                        client.register(b, null, emptyList(), false)
                        client.register(c, null, emptyList(), false)
                        val visible = client.lookup(b, a.id)
                        assertEquals(Presence.ONLINE, visible.presence)
                        assertEquals(true, visible.record?.ready)
                        assertEquals(listOf("127.0.0.1:12345"), visible.record?.directAddresses)
                        assertEquals(Presence.UNKNOWN, client.lookup(c, a.id).presence)
                        now.addAndGet(61_000)
                        client.register(b, null, emptyList(), false)
                        val stale = client.lookup(b, a.id)
                        assertEquals(Presence.OFFLINE, stale.presence)
                        assertNull(stale.record?.relay)
                        assertEquals(emptyList(), stale.record?.directAddresses)
                        now.addAndGet(24 * 60 * 60 * 1_000L)
                        client.register(b, null, emptyList(), false)
                        assertEquals(Presence.UNKNOWN, client.lookup(b, a.id).presence)
                    }
                }
            }
        }
    }

    @Test
    fun `signature and one use challenge prevent impersonation and replay`() {
        CoordinatorService.open().use { service ->
            service.start()
            val base = URI("http://127.0.0.1:${service.port}/")
            val http = HttpClient.newHttpClient()
            IrohProfile.open(root.resolve("A")).use { a ->
                IrohProfile.open(root.resolve("B")).use { b ->
                    fun challenge(): ByteArray = http.send(HttpRequest.newBuilder(base.resolve("challenge")).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body()
                    fun post(body: ByteArray): Int = http.send(HttpRequest.newBuilder(base.resolve("register"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofByteArray()).statusCode()
                    val forged = DiscoveryProtocol.encode(DiscoveryRequest(DiscoveryRequest.Operation.REGISTER, challenge(), a.id))
                    assertEquals(400, post(DiscoveryProtocol.signed(forged, b.signProof(forged))))
                    val valid = DiscoveryProtocol.encode(DiscoveryRequest(DiscoveryRequest.Operation.REGISTER, challenge(), a.id))
                    val signed = DiscoveryProtocol.signed(valid, a.signProof(valid))
                    assertEquals(200, post(signed))
                    assertEquals(401, post(signed))
                    assertTrue(valid.isNotEmpty())
                }
            }
        }
    }
}
