package org.enderlink

import dev.onvoid.webrtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Только канал данных. Сбор ICE завершён до экспорта приглашения; TURN не настраивается. */
internal class WebRtcPeer(stun: String?, private val log: (String) -> Unit = {}) : AutoCloseable {
    private val gathered = CompletableFuture<Unit>()
    private val opened = CompletableFuture<Unit>()
    private val incoming = LinkedBlockingQueue<ByteArray>(256)
    private val stopped = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile private var failure: IOException? = null
    @Volatile private var channel: RTCDataChannel? = null
    @Volatile private var localCandidates = "ещё не собраны"
    @Volatile private var remoteCandidates = "ещё не получены"
    private val factory = PeerConnectionFactory()
    private val peer: RTCPeerConnection

    init {
        try {
            val config = RTCConfiguration()
            if (stun != null) config.iceServers.add(RTCIceServer().apply { urls.add(stun) })
            peer = factory.createPeerConnection(config, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    val type = candidate.sdp.substringAfter(" typ ", "unknown").substringBefore(' ')
                    log("ICE: найден кандидат $type")
                }
                override fun onIceGatheringChange(state: RTCIceGatheringState) {
                    if (state == RTCIceGatheringState.COMPLETE) gathered.complete(Unit)
                }
                override fun onIceCandidateError(event: RTCPeerConnectionIceErrorEvent) {
                    log("ICE/STUN: ${event.errorCode}: ${event.errorText} (${event.url})")
                }
                override fun onIceConnectionChange(state: RTCIceConnectionState) { log("ICE: $state") }
                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    log("Соединение: $state")
                    if (state == RTCPeerConnectionState.FAILED) {
                        log("Кандидаты: локальные [$localCandidates], удалённые [$remoteCandidates]")
                        fail(IOException("WebRTC не установил прямое соединение. TURN/relay отключён. " +
                            "По этому статусу нельзя отличить ограничения NAT/провайдера от блокировки " +
                            "межсетевым экраном или ошибки согласования. Сохраните логи обеих сторон."))
                    } else if (state == RTCPeerConnectionState.CLOSED) stop()
                }
                override fun onSelectedCandidatePairChanged(event: RTCCandidatePairChangeEvent) {
                    log("Выбран путь (TURN не настроен): ${event.local?.sdp} <-> ${event.remote?.sdp}")
                }
                override fun onDataChannel(dataChannel: RTCDataChannel) = attach(dataChannel)
            })
        } catch (e: Throwable) {
            factory.dispose()
            throw e
        }
    }

    fun offer(): String {
        attach(peer.createDataChannel("enderlink-test", RTCDataChannelInit()))
        return localDescription(true)
    }

    fun answer(sdp: String): String {
        setDescription(RTCSessionDescription(RTCSdpType.OFFER, sdp), false)
        return localDescription(false)
    }

    fun acceptAnswer(sdp: String) = setDescription(RTCSessionDescription(RTCSdpType.ANSWER, sdp), false)

    private fun localDescription(offer: Boolean): String {
        val result = CompletableFuture<RTCSessionDescription>()
        val observer = object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { result.complete(description) }
            override fun onFailure(error: String) { result.completeExceptionally(IOException(error)) }
        }
        if (offer) peer.createOffer(RTCOfferOptions(), observer)
        else peer.createAnswer(RTCAnswerOptions(), observer)
        setDescription(await(result, "создание SDP"), true)
        await(gathered, "сбор ICE-кандидатов")
        return peer.localDescription.sdp.also {
            localCandidates = candidateSummary(it)
            log("Локальные ICE-кандидаты: $localCandidates")
        }
    }

    private fun setDescription(description: RTCSessionDescription, local: Boolean) {
        val result = CompletableFuture<Unit>()
        val observer = object : SetSessionDescriptionObserver {
            override fun onSuccess() { result.complete(Unit) }
            override fun onFailure(error: String) { result.completeExceptionally(IOException(error)) }
        }
        if (local) peer.setLocalDescription(description, observer)
        else peer.setRemoteDescription(description, observer)
        await(result, "применение SDP")
        if (!local) {
            remoteCandidates = candidateSummary(description.sdp)
            log("Принят ${description.sdpType}; удалённые ICE-кандидаты: $remoteCandidates")
        }
    }

    private fun attach(dataChannel: RTCDataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(sentDataSize: Long) = Unit
            override fun onStateChange() {
                when (dataChannel.state) {
                    RTCDataChannelState.OPEN -> opened.complete(Unit)
                    RTCDataChannelState.CLOSED -> stop()
                    else -> Unit
                }
            }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                // Нативная память доступна только до возврата из callback.
                if (buffer.data.remaining() > MAX_MESSAGE) {
                    fail(IOException("Получено слишком большое сообщение"))
                    return
                }
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                if (!incoming.offer(bytes)) fail(IOException("Переполнена очередь входящих сообщений"))
            }
        })
        if (dataChannel.state == RTCDataChannelState.OPEN) opened.complete(Unit)
    }

    fun awaitOpen() = await(opened, "открытие канала данных", CONNECT_TIMEOUT_SECONDS)

    fun send(bytes: ByteArray) = synchronized(sendLock) {
        require(bytes.size <= MAX_MESSAGE) { "Максимум $MAX_MESSAGE байт на сообщение" }
        failure?.let { throw it }
        check(!stopped.get()) { "Соединение закрыто" }
        val current = checkNotNull(channel) { "Канал ещё не создан" }
        check(current.state == RTCDataChannelState.OPEN) { "Канал ещё не открыт" }
        check(current.bufferedAmount < 1024 * 1024) { "Очередь отправки заполнена" }
        current.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
    }

    fun receive(): ByteArray? {
        while (true) {
            failure?.let { throw it }
            incoming.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
            if (stopped.get()) return null
        }
    }

    fun stop(error: IOException? = null) {
        if (error != null) failure = error
        stopped.set(true)
        opened.completeExceptionally(IOException("Канал закрыт до подключения"))
    }

    private fun fail(error: IOException) {
        gathered.completeExceptionally(error)
        opened.completeExceptionally(error)
        stop(error)
    }

    override fun close() {
        stop()
        synchronized(sendLock) {
            if (!disposed.compareAndSet(false, true)) return
            try {
                peer.close()
            } finally {
                try {
                    channel?.let { it.unregisterObserver(); it.dispose() }
                } finally {
                    factory.dispose()
                }
            }
        }
    }

    companion object {
        const val MAX_MESSAGE = 16 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 300L

        private fun candidateSummary(sdp: String): String = sdp.lineSequence()
            .filter { it.startsWith("a=candidate:") }
            .map { it.substringAfter(" typ ", "unknown").substringBefore(' ').trim() }
            .groupingBy { it }.eachCount().entries
            .joinToString { "${it.key}=${it.value}" }.ifEmpty { "нет" }

        private fun <T> await(result: CompletableFuture<T>, operation: String, timeoutSeconds: Long = 60): T = try {
            result.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("Таймаут: $operation. Попробуйте новое приглашение или другой STUN.", e)
        } catch (e: ExecutionException) {
            throw IOException("$operation: ${e.cause?.message}", e.cause)
        }
    }
}
