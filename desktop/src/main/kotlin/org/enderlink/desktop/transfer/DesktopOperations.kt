package org.enderlink.desktop.transfer

import org.enderlink.desktop.i18n.messages
import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.iroh.IrohNode
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.WorldTransfer
import org.enderlink.worldtransfer.WorldTransferPhase
import org.enderlink.worldtransfer.WorldTransferStats
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

enum class Route { FRIEND, LOCAL }
data class TransferTarget(val route: Route, val friend: String = "", val host: String = "", val port: Int = 25570)

/** Keeps the current socket or listener closeable so a waiting receive can be cancelled. */
class DesktopOperations(private val profileDirectory: Path = IrohProfile.defaultDirectory()) {
    private val active = AtomicReference<AutoCloseable?>()
    @Volatile private var cancelled = false
    val wasCancelled: Boolean get() = cancelled

    fun begin() { cancelled = false }

    fun cancel() {
        cancelled = true
        runCatching { active.getAndSet(null)?.close() }
    }

    fun send(
        repository: WorldRepository, source: Path?, target: TransferTarget,
        message: (String) -> Unit, progress: (Long, Long) -> Unit,
    ): WorldTransferStats {
        if (source != null) {
            message(messages.text("transfer.publishing"))
            repository.publish(source)
        }
        checkActive()
        return withConnection(target, receiving = false, message) { connection ->
            WorldTransfer.send(repository, connection, progress) { message(phaseText(it)) }
        }
    }

    fun receive(
        repository: WorldRepository, target: TransferTarget,
        message: (String) -> Unit, progress: (Long, Long) -> Unit,
    ): WorldTransferStats {
        return withConnection(target, receiving = true, message) { connection ->
            WorldTransfer.receive(repository, connection, progress) { message(phaseText(it)) }
        }
    }

    private fun <T> withConnection(
        target: TransferTarget, receiving: Boolean, message: (String) -> Unit, action: (Connection) -> T,
    ): T {
        require(target.port in 1..65535) { messages.text("transfer.port.invalid") }
        if (target.route == Route.FRIEND) {
            require(target.friend.isNotBlank()) { messages.text("transfer.friend.required") }
            return IrohProfile.open(profileDirectory).use { profile ->
                IrohNode.open(profile, log = message).use { node ->
                    track(node)
                    val connection = if (receiving) node.accept(IrohPurpose.TRANSFER, target.friend)
                    else node.connect(target.friend, IrohPurpose.TRANSFER)
                    connection.use {
                        track(it)
                        it.readTimeoutMs = 300_000
                        action(it)
                    }
                }
            }
        }
        if (receiving) {
            return TcpListener.open(target.port).use { listener ->
                track(listener)
                message("Ожидание подключения на порту ${listener.port}…")
                listener.accept().use { connection ->
                    track(connection)
                    connection.readTimeoutMs = 300_000
                    action(connection)
                }
            }
        }
        require(target.host.isNotBlank()) { messages.text("transfer.recipientAddress.required") }
        message("Подключаемся к ${target.host}:${target.port}…")
        return TcpConnection.connect(Endpoint(target.host, target.port)).use { connection ->
            track(connection)
            connection.readTimeoutMs = 300_000
            action(connection)
        }
    }

    private fun track(resource: AutoCloseable) {
        if (cancelled) { resource.close(); throw InterruptedException(messages.text("status.cancelled")) }
        active.set(resource)
        if (cancelled && active.compareAndSet(resource, null)) {
            resource.close()
            throw InterruptedException(messages.text("status.cancelled"))
        }
    }

    private fun checkActive() { if (cancelled) throw InterruptedException(messages.text("status.cancelled")) }

    private fun phaseText(phase: WorldTransferPhase): String = when (phase) {
        WorldTransferPhase.MANIFEST -> messages.text("transfer.manifest")
        WorldTransferPhase.OBJECTS -> messages.text("transfer.objects")
        WorldTransferPhase.ACTIVATION -> messages.text("transfer.activation")
        WorldTransferPhase.CONFIRMATION -> messages.text("transfer.confirmation")
    }
}
