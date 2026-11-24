package org.enderlink.network.discovery

import org.enderlink.network.iroh.IrohProfile
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** HTTPS client for presence and address lookup; the profile key signs each operation. */
class DiscoveryClient(private val baseUri: URI, private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5)).build()) {
    init {
        require(baseUri.scheme == "https" || baseUri.scheme == "http" &&
            baseUri.host in setOf("localhost", "127.0.0.1", "::1")) { "Координатору нужен HTTPS" }
        require(baseUri.userInfo == null && baseUri.query == null && baseUri.fragment == null &&
            (baseUri.path.isNullOrEmpty() || baseUri.path == "/")) { "Некорректный адрес координатора" }
    }

    fun register(profile: IrohProfile, relay: String?, addresses: List<String>, ready: Boolean) {
        val request = DiscoveryRequest(DiscoveryRequest.Operation.REGISTER, challenge(), profile.id,
            relay, addresses, profile.friends().values.toSet(), ready)
        post("register", profile, request)
    }

    fun lookup(profile: IrohProfile, targetId: String): DiscoveryResult {
        val request = DiscoveryRequest(DiscoveryRequest.Operation.LOOKUP, challenge(), profile.id, targetId = targetId)
        val started = System.nanoTime()
        val result = DiscoveryProtocol.decodeResult(post("lookup", profile, request))
        return result.copy(coordinatorResponseMs = (System.nanoTime() - started) / 1_000_000)
    }

    private fun challenge(): ByteArray {
        val request = HttpRequest.newBuilder(baseUri.resolve("challenge")).timeout(Duration.ofSeconds(10)).GET().build()
        val response = send(request)
        if (response.status != 200 || response.body.size != 32) throw IOException("Координатор не выдал проверочный код")
        return response.body
    }

    private fun post(path: String, profile: IrohProfile, request: DiscoveryRequest): ByteArray {
        val payload = DiscoveryProtocol.encode(request)
        val body = DiscoveryProtocol.signed(payload, profile.signProof(payload))
        val message = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        val response = send(message)
        if (response.status != 200) throw IOException("Координатор ответил ${response.status}: " +
            response.body.toString(Charsets.UTF_8).take(200))
        return response.body
    }

    private data class Response(val status: Int, val body: ByteArray)

    private fun send(request: HttpRequest): Response = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val body = input.readNBytes(DiscoveryProtocol.MAX_BODY + 1)
            if (body.size > DiscoveryProtocol.MAX_BODY) throw IOException("Слишком большой ответ координатора")
            Response(response.statusCode(), body)
        }
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Обращение к координатору прервано", error)
    } catch (error: IOException) { throw IOException("Координатор недоступен: ${error.message}", error) }
}
