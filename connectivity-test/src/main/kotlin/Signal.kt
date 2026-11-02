package org.enderlink

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Полный SDP с уже собранными ICE-кандидатами: один файл на каждую сторону. */
internal object Signal {
    private const val LIMIT = 256 * 1024

    fun encode(kind: String, sdp: String): String {
        require(kind in setOf("offer", "answer"))
        return "ELRTC1.$kind." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sdp.toByteArray(Charsets.UTF_8)) + "\n"
    }

    fun read(path: Path, expected: String): String {
        val bytes = Files.newInputStream(path).use { it.readNBytes(LIMIT + 1) }
        require(bytes.size <= LIMIT) { "Слишком большой файл приглашения" }
        return decode(bytes.toString(Charsets.UTF_8), expected)
    }

    fun decode(text: String, expected: String): String {
        require(text.length <= LIMIT) { "Слишком большое приглашение" }
        val parts = text.filterNot(Char::isWhitespace).split('.', limit = 3)
        require(parts.size == 3 && parts[0] == "ELRTC1" && parts[1] == expected) {
            "Нужен файл $expected формата ELRTC1"
        }
        val sdp = try {
            Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Повреждён код приглашения", e)
        }
        require(sdp.startsWith("v=0\r\n") && sdp.contains("m=application ")) {
            "Приглашение не содержит SDP канала данных"
        }
        require(!sdp.contains(" typ relay")) { "Этот тест не принимает relay-кандидаты" }
        return sdp
    }
}
