package org.enderlink.network.iroh

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.IrohException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal suspend fun dialFriend(
    endpoint: Endpoint, profile: IrohProfile, target: String, explicitRelay: String?, log: (String) -> Unit,
): Connection {
    EndpointId.fromString(target).use { id ->
        suspend fun attempt(relay: String?): Connection = EndpointAddr(id, relay, emptyList()).use { addr ->
            withTimeout(60_000) { endpoint.connect(addr, IROH_ALPN) }
        }
        try {
            if (explicitRelay != null) {
                log("Relay друга задан явно: $explicitRelay")
                return attempt(explicitRelay)
            }
            val savedRelay = profile.relayFor(target)
            if (savedRelay != null) {
                log("Используется сохранённый relay друга: $savedRelay")
                try { return attempt(savedRelay) }
                catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    log("Сохранённый relay не сработал. Повторный поиск друга по ID…")
                }
            }
            return attempt(null)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            endpoint.remoteAddr(id)?.use { known ->
                log("Известный relay друга: ${known.relayUrl() ?: "нет"}")
                log("Известных прямых адресов друга: ${known.directAddresses().size}")
            } ?: log("Адрес друга не появился в локальном кеше поиска.")
            val reason = if (e is IrohException) e.debugMessage() else e.message
            throw IOException("Подключение к $target не удалось: $reason. " +
                "Проверьте ID и оба работающих процесса. Для диагностики повторите с --debug; " +
                "relay из вывода listen можно передать через --relay.", e)
        }
    }
}
