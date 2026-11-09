package org.enderlink.network.iroh

import computer.iroh.Connection as NativeConnection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.LogLevel
import computer.iroh.IrohException
import computer.iroh.RelayMode
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import computer.iroh.presetN0
import computer.iroh.setLogLevel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

internal val IROH_ALPN = "enderlink/connection/1".toByteArray(Charsets.US_ASCII)
enum class IrohPurpose { CHAT, TRANSFER, DIAGNOSTIC, GAME }

/** Профиль должен оставаться открытым до закрытия узла и всех его соединений. */
class IrohNode internal constructor(
    private val endpoint: Endpoint,
    private val profile: IrohProfile,
    private val log: (String) -> Unit,
    private val shutdownEndpoint: suspend (Endpoint) -> Unit = { it.shutdown() },
    private val trace: (String) -> Unit = {},
) : AutoCloseable {
    fun connect(friend: String, purpose: IrohPurpose = IrohPurpose.CHAT, relay: String? = null): IrohConnection = blocking {
        if (relay != null) validateRelay(relay)
        val target = profile.friendId(friend)
        log("Подключение к $friend; ID друга: $target")
        prepare(dialFriend(endpoint, profile, target, relay, log), purpose, true)
    }

    fun accept(purpose: IrohPurpose = IrohPurpose.CHAT, friend: String? = null, timeoutMs: Long = 0): IrohConnection = blocking {
        require(timeoutMs >= 0) { "Таймаут не может быть отрицательным" }
        if (timeoutMs == 0L) acceptIncoming(purpose, friend) else withTimeout(timeoutMs) { acceptIncoming(purpose, friend) }
    }

    private suspend fun acceptIncoming(purpose: IrohPurpose, friend: String?): IrohConnection {
        val expectedId = friend?.let(profile::friendId)
        require(profile.friends().isNotEmpty()) { "Сначала добавьте друга командой add <имя> <ID>" }
        log("Ожидание сохранённого друга…")
        while (true) {
            val incoming = endpoint.acceptNext() ?: throw IOException("Узел закрыт")
            val connection = incoming.use {
                try { withTimeout(20_000) { it.accept().use { accepting -> accepting.connect() } } }
                catch (_: kotlinx.coroutines.TimeoutCancellationException) { log("Таймаут входящего подключения"); null }
                catch (e: Exception) { log("Не удалось принять соединение: ${e.message}"); null }
            } ?: continue
            val id = connection.remoteId().use { it.toString() }
            val name = profile.friendName(id)
            if (name != null && (expectedId == null || expectedId == id)) {
                log("Подключился друг $name ($id)")
                return prepare(connection, purpose, false)
            }
            log("Отклонён неожидаемый ID: $id")
            try { connection.close(1L, "unknown friend".toByteArray()) } finally { connection.close() }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    internal suspend fun prepare(native: NativeConnection, purpose: IrohPurpose, initiator: Boolean): IrohConnection {
        val confirmedRelay = AtomicReference<String?>(null)
        val lastRoute = AtomicReference<String?>(null)
        fun report(paths: List<PathSnapshot>) {
            paths.firstOrNull { it.isSelected && it.isRelay }?.let { confirmedRelay.set(it.remoteAddr) }
            val route = paths.filter { it.isSelected }.joinToString { "${it.id}:${it.remoteAddr}:${it.isRelay}" }
            if (lastRoute.getAndSet(route) != route) log(describePaths(paths))
        }
        val watcher = native.watchPaths(object : PathChangeCallback {
            override suspend fun onChange(paths: List<PathSnapshot>) { report(paths) }
        })
        var stage = if (initiator) "открытие исходящего QUIC-потока" else "ожидание входящего QUIC-потока"
        try {
            report(native.paths())
            trace("iroh: $stage")
            val stream = withTimeout(30_000) { if (initiator) native.openBi() else native.acceptBi() }
            trace("iroh: QUIC-поток открыт")
            val connection = IrohConnection(native, stream, watcher)
            try {
                connection.readTimeoutMs = 30_000
                val hello = "ELIROH2:${purpose.name}".toByteArray(Charsets.US_ASCII)
                if (initiator) {
                    stage = "отправка приветствия ($purpose)"
                    trace("iroh: $stage")
                    connection.send(hello)
                    stage = "ожидание ответа на приветствие ($purpose)"
                    trace("iroh: $stage")
                    if (connection.receive()?.contentEquals(hello) != true) throw IOException("Режимы подключения участников не совпадают")
                } else {
                    stage = "ожидание приветствия ($purpose)"
                    trace("iroh: $stage")
                    if (connection.receive()?.contentEquals(hello) != true) {
                        connection.send("Режимы подключения участников не совпадают".toByteArray())
                        throw IOException("Ожидался режим $purpose, собеседник выбрал другой режим")
                    }
                    stage = "отправка ответа на приветствие ($purpose)"
                    trace("iroh: $stage")
                    connection.send(hello)
                }
                trace("iroh: приветствие подтверждено ($purpose)")
                connection.readTimeoutMs = 0
                if (initiator) confirmedRelay.get()?.let { relay ->
                    try {
                        val remote = connection.remoteId
                        if (profile.rememberRelay(remote, relay)) log("Relay друга сохранён для следующих подключений: $relay")
                    } catch (e: IOException) { log("Не удалось сохранить relay друга: ${e.message}") }
                }
                return connection
            } catch (e: Throwable) { connection.abort(); throw e }
        } catch (e: Throwable) {
            // prepare мог завершиться до создания адаптера.
            runCatching { watcher.stop(); watcher.close() }
            runCatching { native.close(1L, "handshake failed".toByteArray()); native.close() }
            if (e is kotlinx.coroutines.TimeoutCancellationException || e is SocketTimeoutException) {
                throw SocketTimeoutException("Таймаут iroh: $stage").apply { initCause(e) }
            }
            throw e
        }
    }

    override fun close() {
        try {
            runBlocking { withTimeout(10_000) { shutdownEndpoint(endpoint) } }
        } catch (e: Exception) {
            // Ошибка очистки не должна скрывать результат операции или её исходную ошибку.
            log("Предупреждение: iroh не завершил остановку узла: ${e.message}")
        }
        try {
            endpoint.close()
        } catch (e: Exception) {
            log("Предупреждение: iroh не закрыл узел: ${e.message}")
        }
    }

    companion object {
        fun open(profile: IrohProfile, debug: Boolean = false, homeRelay: String? = null, log: (String) -> Unit = ::println): IrohNode = blocking {
            homeRelay?.let(::validateRelay)
            if (debug) setLogLevel(LogLevel.DEBUG)
            log("Ваш ID: ${profile.id}")
            log("Подключение к публичной сети iroh…")
            val relayMode = homeRelay?.let { RelayMode.customFromUrls(listOf(it)) }
            val endpoint = try {
                Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = profile.key,
                    alpns = listOf(IROH_ALPN), relayMode = relayMode))
            } finally { relayMode?.close() }
            try {
                withTimeout(60_000) { endpoint.online() }
                log("Узел онлайн.")
                endpoint.addr().use { log("Мой relay: ${it.relayUrl() ?: "пока не определён"}") }
                IrohNode(endpoint, profile, log, trace = { if (debug) log(it) })
            } catch (e: Throwable) {
                runCatching { endpoint.shutdown() }; endpoint.close(); throw e
            }
        }

        private fun <T> blocking(block: suspend () -> T): T = try { runBlocking { block() } }
        catch (e: IOException) { throw e }
        catch (e: IllegalArgumentException) { throw e }
        catch (e: IrohException) { throw IOException("Ошибка iroh: ${e.debugMessage()}", e) }
        catch (e: Exception) { throw IOException("Ошибка iroh: ${e.message}", e) }
    }
}
