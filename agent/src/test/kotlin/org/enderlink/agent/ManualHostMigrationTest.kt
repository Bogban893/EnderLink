package org.enderlink.agent

import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransfer
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Timeout(20)
class ManualHostMigrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `сохранение передача запуск нового хоста и возврат к старой версии`() {
        val a = WorldRepository(root.resolve("A"))
        val b = WorldRepository(root.resolve("B"))
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("level.dat"), "original world")
        val first = a.publish(source)

        val hostA = VanillaServer(a, session("session-A"), first.versionId)
        hostA.prepare()
        launchFake(hostA)
        val changed = hostA.stopAndPublish()
        assertNotEquals(first.versionId, changed)

        TcpListener.open(0, "127.0.0.1").use { listener ->
            TcpConnection.connect(Endpoint("127.0.0.1", listener.port)).use { client ->
                listener.accept(2_000).use { server ->
                    client.readTimeoutMs = 5_000
                    server.readTimeoutMs = 5_000
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val receiving = executor.submit { WorldTransfer.receive(b, server) }
                        WorldTransfer.send(a, client)
                        receiving.get(10, TimeUnit.SECONDS)
                    } finally { executor.shutdownNow() }
                }
            }
        }
        assertEquals(changed, b.current()?.versionId)
        val hostB = VanillaServer(b, session("session-B"), changed)
        hostB.prepare()
        assertEquals("updated world", Files.readString(hostB.worldDirectory.resolve("level.dat")))
        launchFake(hostB)
        hostB.stopAndPublish()

        a.checkout(first.versionId)
        val returned = VanillaServer(a, session("session-return"), first.versionId)
        returned.prepare()
        assertEquals("original world", Files.readString(returned.worldDirectory.resolve("level.dat")))
    }

    private fun session(name: String): Path = Files.createDirectory(root.resolve(name)).also {
        Files.writeString(it.resolve("eula.txt"), "eula=true\n")
    }

    private fun launchFake(server: VanillaServer) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = FakeServerProcess::class.java.protectionDomain.codeSource.location.toURI().path
        server.start(listOf(java, "-cp", classes, FakeServerProcess::class.java.name))
    }
}
