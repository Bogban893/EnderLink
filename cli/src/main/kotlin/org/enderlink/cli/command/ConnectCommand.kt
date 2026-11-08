package org.enderlink.cli.command

import org.enderlink.cli.console.ChatSession
import org.enderlink.cli.CliOptions
import org.enderlink.network.iroh.IrohPurpose

class ConnectCommand {
    fun run(options: CliOptions) {
        withConnection(options, IrohPurpose.CHAT) { connection ->
            ChatSession().run(connection)
        }
    }
}
