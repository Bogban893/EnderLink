package org.enderlink.desktop.transfer

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.minecraft.MinecraftSaves
import org.enderlink.network.connection.Connection
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.discovery.DiscoveryClient
import org.enderlink.network.discovery.DiscoveryRecord
import org.enderlink.network.discovery.Presence
import org.enderlink.network.iroh.IrohNode
import org.enderlink.network.iroh.IrohConnection
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.network.iroh.IrohPurpose
import org.enderlink.network.tcp.TcpConnection
import org.enderlink.network.tcp.TcpListener
import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldManifest
import org.enderlink.worldtransfer.WorldTransfer
import org.enderlink.worldtransfer.WorldTransferPhase
import org.enderlink.worldtransfer.WorldTransferStats
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

enum class Route { FRIEND, LOCAL }
data class TransferTarget(val route: Route, val friend: String = "", val host: String = "", val port: Int = 25570)
class WorldPublicationException(cause: IOException) : IOException(cause.message, cause)

/** Keeps the current socket or listener closeable so a waiting receive can be cancelled. */
class DesktopOperations(private val profileDirectory: Path = IrohProfile.defaultDirectory()) {
    private val active = AtomicReference<AutoCloseable?>()
    @Volatile private var cancelled = false
    val wasCancelled: Boolean get() = cancelled
    @Volatile var coordinatorUrl: String = ""

    fun begin() { cancelled = false }

    fun cancel() {
        cancelled = true
        runCatching { active.getAndSet(null)?.close() }
    }

    fun send(
        repository: WorldRepository, source: Path?, target: TransferTarget,
        message: (String) -> Unit, progress: (Long, Long) -> Unit,
        owner: UUID? = null,
        guest: UUID? = null,
    ): WorldTransferStats {
        if (source != null) {
            message(messages.text("transfer.publishing"))
            try {
                MinecraftSaves.requireClosed(source)
                if (owner == null) repository.publish(source) else repository.publishSingleplayer(source, owner, guest)
            }
            catch (error: IOException) { throw WorldPublicationException(error) }
        }
        checkActive()
        return withConnection(target, receiving = false, message) { connection ->
            WorldTransfer.send(repository, connection, progress) {
                message(phaseText(it))
                if (it == WorldTransferPhase.OBJECTS) connection.setWorldReadTimeout(300_000)
            }
        }
    }

    fun receive(
        repository: WorldRepository, target: TransferTarget,
        message: (String) -> Unit, progress: (Long, Long) -> Unit,
        approve: (WorldManifest, String?) -> Boolean = { _, _ -> true },
    ): WorldTransferStats {
        return withConnection(target, receiving = true, message) { connection ->
            val peerId = (connection as? IrohConnection)?.remoteId
            WorldTransfer.receive(repository, connection, progress, { approve(it, peerId) }) { message(phaseText(it)) }
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
                    val client = coordinatorUrl.takeIf(String::isNotBlank)?.let { raw ->
                        runCatching { DiscoveryClient(URI.create(raw)) }.getOrElse {
                            message(messages.text("discovery.invalidAddress", it.message))
                            null
                        }
                    }
                    fun register(ready: Boolean) {
                        if (client != null) try {
                            val address = node.advertisedAddress()
                            client.register(profile, address.first, address.second, ready)
                        }
                        catch (error: IOException) { message(messages.text("discovery.unavailable", error.message)) }
                    }
                    register(receiving)
                    val heartbeatStop = AtomicBoolean(false)
                    val heartbeat = if (client == null) null else Thread({
                        while (!heartbeatStop.get()) {
                            try { Thread.sleep(20_000) } catch (_: InterruptedException) { break }
                            if (!heartbeatStop.get()) register(receiving)
                        }
                    }, "enderlink-discovery-heartbeat").apply { isDaemon = true; start() }
                    try {
                        val known: DiscoveryRecord? = if (receiving || client == null) null else try {
                            val result = client.lookup(profile, profile.friendId(target.friend))
                            val lastSeen = result.record?.let { record ->
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(record.lastSeenMs))
                            }.orEmpty()
                            message(messages.text(when (result.presence) {
                                Presence.ONLINE -> if (result.record?.ready == true) "discovery.ready" else "discovery.notReady"
                                Presence.OFFLINE -> "discovery.offline"
                                Presence.UNKNOWN -> "discovery.unknown"
                            }, target.friend, lastSeen, result.coordinatorResponseMs.toString()))
                            result.record?.takeIf { result.presence == Presence.ONLINE }
                        } catch (error: IOException) {
                            message(messages.text("discovery.unavailable", error.message))
                            null
                        }
                        val connection = if (receiving) node.accept(IrohPurpose.TRANSFER, target.friend)
                        else node.connect(target.friend, IrohPurpose.TRANSFER, known = known)
                        connection.use {
                            track(it)
                            it.readTimeoutMs = if (receiving) 300_000 else 60_000
                            action(it)
                        }
                    } finally {
                        heartbeatStop.set(true)
                        heartbeat?.interrupt()
                        heartbeat?.join(1_000)
                        if (receiving) register(false)
                    }
                }
            }
        }
        if (receiving) {
            return TcpListener.open(target.port).use { listener ->
                track(listener)
                message(messages.text("transfer.waiting", listener.port.toString()))
                listener.accept().use { connection ->
                    track(connection)
                    connection.readTimeoutMs = 300_000
                    action(connection)
                }
            }
        }
        require(target.host.isNotBlank()) { messages.text("transfer.recipientAddress.required") }
        message(messages.text("transfer.connecting", target.host, target.port.toString()))
        return TcpConnection.connect(Endpoint(target.host, target.port)).use { connection ->
            track(connection)
            connection.readTimeoutMs = 60_000
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

    private fun Connection.setWorldReadTimeout(timeoutMs: Int) {
        when (this) {
            is TcpConnection -> readTimeoutMs = timeoutMs
            is IrohConnection -> readTimeoutMs = timeoutMs
        }
    }

    private fun phaseText(phase: WorldTransferPhase): String = when (phase) {
        WorldTransferPhase.MANIFEST -> messages.text("transfer.manifest")
        WorldTransferPhase.READINESS -> messages.text("transfer.readiness")
        WorldTransferPhase.OBJECTS -> messages.text("transfer.objects")
        WorldTransferPhase.ACTIVATION -> messages.text("transfer.activation")
        WorldTransferPhase.CONFIRMATION -> messages.text("transfer.confirmation")
    }
}
