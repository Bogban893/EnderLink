package org.enderlink.cli.command

import org.enderlink.cli.console.ChatSession
import org.enderlink.network.tcp.TcpListener

class ListenCommand {
    fun run(host: String?, port: Int) {
        TcpListener.open(port, host).use { listener ->
            println("Ожидание подключения на ${host ?: "0.0.0.0"}:${listener.port}")
            listener.accept().use { connection ->
                ChatSession().run(connection)
            }
        }
    }
}
