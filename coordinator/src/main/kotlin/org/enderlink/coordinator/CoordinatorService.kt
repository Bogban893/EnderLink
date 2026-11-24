package org.enderlink.coordinator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.enderlink.network.discovery.DiscoveryProtocol
import org.enderlink.network.discovery.DiscoveryRecord
import org.enderlink.network.discovery.DiscoveryRequest
import org.enderlink.network.discovery.DiscoveryResult
import org.enderlink.network.discovery.Presence
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Ephemeral directory. TLS termination belongs to the reverse proxy on the public host. */
class CoordinatorService private constructor(
    private val server: HttpServer,
    private val now: () -> Long,
) : AutoCloseable {
    private val random = SecureRandom()
    private val challenges = ConcurrentHashMap<String, Long>()
    private val records = ConcurrentHashMap<String, DiscoveryRecord>()
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "enderlink-coordinator").apply { isDaemon = true }
    }
    val port: Int get() = server.address.port

    init {
        server.executor = workers
        server.createContext("/challenge") { exchange -> handle(exchange) {
            requireMethod(exchange, "GET")
            val current = now()
            challenges.entries.removeIf { it.value < current }
            if (challenges.size >= 10_000) throw ServiceError(503, "Слишком много запросов")
            val nonce = ByteArray(32).also(random::nextBytes)
            challenges[Base64.getEncoder().encodeToString(nonce)] = current + 30_000
            reply(exchange, 200, nonce)
        } }
        server.createContext("/register") { exchange -> handle(exchange) {
            requireMethod(exchange, "POST")
            val request = authenticated(exchange, DiscoveryRequest.Operation.REGISTER)
            val current = now()
            // Keep recent offline state, but never let old IDs permanently fill the directory.
            records.entries.removeIf { current - it.value.lastSeenMs > 24 * 60 * 60 * 1_000L }
            if (records.size >= 10_000 && !records.containsKey(request.signerId)) throw ServiceError(503, "Каталог заполнен")
            records[request.signerId] = DiscoveryRecord(request.signerId, request.relay,
                request.directAddresses, request.allowedIds, request.ready, current)
            reply(exchange, 200, byteArrayOf())
        } }
        server.createContext("/lookup") { exchange -> handle(exchange) {
            requireMethod(exchange, "POST")
            val request = authenticated(exchange, DiscoveryRequest.Operation.LOOKUP)
            val current = now()
            val requester = records[request.signerId]
            if (requester == null || current - requester.lastSeenMs > 60_000) throw ServiceError(401, "Сначала зарегистрируйтесь")
            val target = records[request.targetId]
            val result = when {
                target == null || request.signerId !in target.allowedIds -> DiscoveryResult(Presence.UNKNOWN, null)
                current - target.lastSeenMs > 60_000 -> DiscoveryResult(Presence.OFFLINE,
                    target.copy(relay = null, directAddresses = emptyList(), ready = false, allowedIds = emptySet()))
                else -> DiscoveryResult(Presence.ONLINE, target.copy(allowedIds = emptySet()))
            }
            reply(exchange, 200, DiscoveryProtocol.encodeResult(result))
        } }
    }

    fun start() = server.start()

    private fun authenticated(exchange: HttpExchange, operation: DiscoveryRequest.Operation): DiscoveryRequest {
        val body = exchange.requestBody.use { it.readNBytes(DiscoveryProtocol.MAX_BODY + 69) }
        val (payload, signature) = DiscoveryProtocol.decodeSigned(body)
        val request = DiscoveryProtocol.verify(payload, signature)
        if (request.operation != operation) throw ServiceError(400, "Неверная операция")
        val token = Base64.getEncoder().encodeToString(request.nonce)
        val expires = challenges.remove(token) ?: throw ServiceError(401, "Неизвестный проверочный код")
        if (expires < now()) throw ServiceError(401, "Проверочный код истёк")
        return request
    }

    private fun handle(exchange: HttpExchange, action: () -> Unit) {
        try {
            if (exchange.requestURI.rawQuery != null || exchange.requestURI.path !in setOf("/challenge", "/register", "/lookup"))
                throw ServiceError(404, "Не найдено")
            action()
        } catch (error: ServiceError) { reply(exchange, error.status, error.message.toByteArray(Charsets.UTF_8)) }
        catch (_: Exception) { reply(exchange, 400, "Некорректный запрос".toByteArray(Charsets.UTF_8)) }
        finally { exchange.close() }
    }

    private fun requireMethod(exchange: HttpExchange, method: String) {
        if (exchange.requestMethod != method) throw ServiceError(405, "Метод не поддерживается")
    }

    private fun reply(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
    }

    companion object {
        fun open(host: String = "127.0.0.1", port: Int = 0, now: () -> Long = System::currentTimeMillis): CoordinatorService {
            require(port in 0..65535)
            val server = HttpServer.create(InetSocketAddress(host, port), 32)
            return CoordinatorService(server, now)
        }
    }
}

private class ServiceError(val status: Int, override val message: String) : IOException(message)
