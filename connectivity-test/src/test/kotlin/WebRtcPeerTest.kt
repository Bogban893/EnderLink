package org.enderlink

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebRtcPeerTest {
    @Test
    fun `две стороны обмениваются полным SDP и передают данные без STUN и TURN`() {
        WebRtcPeer(null).use { first ->
            WebRtcPeer(null).use { second ->
                val offer = Signal.decode(Signal.encode("offer", first.offer()), "offer")
                val answer = Signal.decode(Signal.encode("answer", second.answer(offer)), "answer")
                first.acceptAnswer(answer)
                first.awaitOpen()
                second.awaitOpen()
                val bytes = ByteArray(WebRtcPeer.MAX_MESSAGE) { (it % 251).toByte() }
                first.send(bytes)
                assertContentEquals(bytes, receive(second))
                val reply = "Ответ: привет 🌍".toByteArray(Charsets.UTF_8)
                second.send(reply)
                assertContentEquals(reply, receive(first))
                first.send(byteArrayOf())
                assertContentEquals(byteArrayOf(), receive(second))
                assertFailsWith<IllegalArgumentException> { first.send(ByteArray(WebRtcPeer.MAX_MESSAGE + 1)) }
                second.stop()
                assertNull(second.receive())
            }
        }
    }

    @Test
    fun `формат приглашения проверяет версию тип размер и relay`() {
        val sdp = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val code = Signal.encode("offer", sdp)
        assertEquals(sdp, Signal.decode(code.chunked(40).joinToString("\n"), "offer"))
        assertFailsWith<IllegalArgumentException> { Signal.decode(code, "answer") }
        assertFailsWith<IllegalArgumentException> { Signal.decode(code.replace("ELRTC1", "ELRTC2"), "offer") }
        assertFailsWith<IllegalArgumentException> { Signal.decode("ELRTC1.offer.!!!", "offer") }
        assertFailsWith<IllegalArgumentException> { Signal.decode("x".repeat(256 * 1024 + 1), "offer") }
        assertFailsWith<IllegalArgumentException> {
            Signal.decode(Signal.encode("offer", sdp + "a=candidate:1 1 UDP 1 1.2.3.4 1234 typ relay\r\n"), "offer")
        }
    }

    private fun receive(peer: WebRtcPeer): ByteArray? =
        CompletableFuture.supplyAsync { peer.receive() }.get(10, TimeUnit.SECONDS)
}
