package org.enderlink.cli.command

import org.enderlink.cli.console.ChatSession
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection

class ConnectCommand {
    fun run(host: String, port: Int) {
        println("Подключение к $host:$port")
        TcpConnection.connect(Endpoint(host, port)).use { connection ->
            ChatSession().run(connection)
        }
    }
}
