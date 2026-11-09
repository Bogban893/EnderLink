package org.enderlink.agent

import org.enderlink.network.connection.Connection
import org.enderlink.network.iroh.IrohNode
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.network.iroh.validateRelay
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Один игровой TCP поток переносится кадрами через одно соединение iroh. */
internal object GameTunnel {
    fun host(options: Map<String, String>) {
        val peer = requireNotNull(options["--peer"]) { "Укажите --peer" }
        val targetHost = options["--target-host"] ?: "127.0.0.1"
        val targetPort = port(options["--target-port"])
        val profilePath = options["--profile"]?.let(Path::of) ?: IrohProfile.defaultDirectory()
        IrohProfile.open(profilePath).use { profile ->
            IrohNode.open(profile).use { node ->
                println("Игровой туннель ждёт друга $peer; сервер $targetHost:$targetPort")
                while (true) {
                    node.accept(IrohPurpose.GAME, peer).use { connection ->
                        try {
                            Socket().use { game ->
                                game.connect(InetSocketAddress(targetHost, targetPort), 5_000)
                                bridge(game, connection)
                            }
                        } catch (e: IOException) { System.err.println("Игровое соединение прервано: ${e.message}") }
                    }
                }
            }
        }
    }

    fun join(options: Map<String, String>) {
        val peer = requireNotNull(options["--peer"]) { "Укажите --peer" }
        val localPort = port(options["--local-port"])
        val relay = options["--relay"]?.also(::validateRelay)
        val profilePath = options["--profile"]?.let(Path::of) ?: IrohProfile.defaultDirectory()
        IrohProfile.open(profilePath).use { profile ->
            IrohNode.open(profile).use { node ->
                ServerSocket().use { listener ->
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress("127.0.0.1", localPort))
                    println("Подключите Minecraft к 127.0.0.1:${listener.localPort}; друг $peer")
                    while (true) {
                        listener.accept().use { game ->
                            try {
                                node.connect(peer, IrohPurpose.GAME, relay).use { connection -> bridge(game, connection) }
                            } catch (e: IOException) { System.err.println("Игровое соединение прервано: ${e.message}") }
                        }
                    }
                }
            }
        }
    }

    private fun port(value: String?): Int {
        val number = value?.toIntOrNull() ?: if (value == null) 25565 else throw IllegalArgumentException("Порт должен быть целым числом")
        require(number in 1..65535) { "Порт должен быть от 1 до 65535" }
        return number
    }

    fun bridge(game: Socket, remote: Connection) {
        game.tcpNoDelay = true
        val failure = AtomicReference<IOException?>(null)
        val outgoing = Thread({
            try {
                val input = game.getInputStream()
                val bytes = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count == -1) break
                    if (count > 0) remote.send(bytes.copyOf(count))
                }
                remote.send(ByteArray(0)) // TCP half-close
            } catch (e: IOException) {
                failure.set(e)
                runCatching { remote.close() }
                runCatching { game.close() }
            }
        }, "enderlink-game-outgoing")
        outgoing.isDaemon = true
        outgoing.start()
        try {
            val output = game.getOutputStream()
            var transportClosed = false
            while (true) {
                val bytes = remote.receive()
                if (bytes == null) { transportClosed = true; break }
                if (bytes.isEmpty()) break
                output.write(bytes)
                output.flush()
            }
            if (transportClosed) {
                game.close()
                remote.close()
            } else game.shutdownOutput()
            outgoing.join()
            failure.get()?.let { throw it }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Игровой туннель прерван", e)
        } finally {
            runCatching { remote.close() }
            runCatching { game.close() }
        }
    }
}
