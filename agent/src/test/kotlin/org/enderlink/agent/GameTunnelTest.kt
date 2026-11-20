package org.enderlink.agent

import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@Timeout(15)
class GameTunnelTest {
    @Test
    fun `передаёт TCP в обе стороны и принимает новое соединение после закрытия`() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(2) { runSession(pool, "game packet $it".toByteArray()) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `обрыв транспорта закрывает локальный сокет`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { local ->
                Socket("127.0.0.1", local.localPort).use { client ->
                    local.accept().use { game ->
                        TcpListener.open(0, "127.0.0.1").use { transport ->
                            TcpConnection.connect(Endpoint("127.0.0.1", transport.port)).use { joined ->
                                transport.accept(2_000).use { remote ->
                                    val bridging = pool.submit { runCatching { GameTunnel.bridge(game, joined) } }
                                    remote.close()
                                    bridging.get(5, TimeUnit.SECONDS)
                                    assertEquals(-1, client.getInputStream().read())
                                }
                            }
                        }
                    }
                }
            }
        } finally { pool.shutdownNow() }
    }

    private fun runSession(pool: java.util.concurrent.ExecutorService, message: ByteArray) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { echoListener ->
            val echo = pool.submit {
                echoListener.accept().use { socket ->
                    socket.getInputStream().copyTo(socket.getOutputStream())
                }
            }
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { localListener ->
                Socket("127.0.0.1", localListener.localPort).use { client ->
                    localListener.accept().use { joinedGame ->
                        Socket("127.0.0.1", echoListener.localPort).use { hostedGame ->
                            TcpListener.open(0, "127.0.0.1").use { transport ->
                                TcpConnection.connect(Endpoint("127.0.0.1", transport.port)).use { joinedIroh ->
                                    transport.accept(2_000).use { hostedIroh ->
                                        val join = pool.submit { GameTunnel.bridge(joinedGame, joinedIroh) }
                                        val host = pool.submit { GameTunnel.bridge(hostedGame, hostedIroh) }
                                        client.getOutputStream().write(message)
                                        client.shutdownOutput()
                                        assertContentEquals(message, client.getInputStream().readNBytes(message.size))
                                        assertEquals(-1, client.getInputStream().read())
                                        join.get(5, TimeUnit.SECONDS)
                                        host.get(5, TimeUnit.SECONDS)
                                        echo.get(5, TimeUnit.SECONDS)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
