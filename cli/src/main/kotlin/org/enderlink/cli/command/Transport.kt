package org.enderlink.cli.command

import org.enderlink.cli.CliOptions
import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.iroh.IrohNode
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import java.nio.file.Path

internal fun <T> withConnection(options: CliOptions, purpose: IrohPurpose, acceptTimeoutMs: Int = 0, action: (Connection) -> T): T {
    val receiving = options.command in setOf("listen", "receive", "world-receive")
    if (options.iroh) {
        val directory = options.profile?.let(Path::of) ?: IrohProfile.defaultDirectory()
        IrohProfile.open(directory).use { profile ->
            IrohNode.open(profile, options.debug, homeRelay = options.homeRelay).use { node ->
                val connection = if (receiving) node.accept(purpose, options.peer, acceptTimeoutMs.toLong())
                else node.connect(requireNotNull(options.peer), purpose, options.relay)
                connection.use {
                    if (purpose == IrohPurpose.TRANSFER) it.readTimeoutMs = if (options.command.startsWith("world-")) 300_000 else 30_000
                    return action(it)
                }
            }
        }
    }
    fun use(connection: TcpConnection): T = connection.use {
        if (purpose == IrohPurpose.TRANSFER) it.readTimeoutMs = if (options.command.startsWith("world-")) 300_000 else 30_000
        action(it)
    }
    if (receiving) {
        TcpListener.open(options.port, options.host).use { listener ->
            println("Ожидание подключения на ${options.host ?: "0.0.0.0"}:${listener.port}")
            return use(listener.accept(acceptTimeoutMs))
        }
    }
    println("Подключение к ${options.host}:${options.port}")
    return use(TcpConnection.connect(Endpoint(requireNotNull(options.host), options.port)))
}
