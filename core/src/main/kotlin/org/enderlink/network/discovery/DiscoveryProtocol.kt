package org.enderlink.network.discovery

import computer.iroh.EndpointId
import computer.iroh.Signature
import org.enderlink.network.iroh.validateRelay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Signed, bounded messages for the optional discovery coordinator. No world data passes through it. */
data class DiscoveryRequest(
    val operation: Operation,
    val nonce: ByteArray,
    val signerId: String,
    val relay: String? = null,
    val directAddresses: List<String> = emptyList(),
    val allowedIds: Set<String> = emptySet(),
    val ready: Boolean = false,
    val targetId: String? = null,
) {
    enum class Operation { REGISTER, LOOKUP }
}

data class DiscoveryRecord(
    val id: String,
    val relay: String?,
    val directAddresses: List<String>,
    val allowedIds: Set<String>,
    val ready: Boolean,
    val lastSeenMs: Long,
)

enum class Presence { ONLINE, OFFLINE, UNKNOWN }
data class DiscoveryResult(val presence: Presence, val record: DiscoveryRecord?, val coordinatorResponseMs: Long? = null)

object DiscoveryProtocol {
    private const val MAGIC = 0x454c4431 // ELD1
    const val MAX_BODY = 32 * 1024
    private const val MAX_ADDRESSES = 8
    private const val MAX_FRIENDS = 200

    fun encode(request: DiscoveryRequest): ByteArray {
        validate(request)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(request.operation.ordinal)
            out.write(request.nonce)
            out.writeUTF(request.signerId)
            out.writeBoolean(request.ready)
            out.writeUTF(request.relay.orEmpty())
            out.writeByte(request.directAddresses.size)
            request.directAddresses.forEach(out::writeUTF)
            out.writeShort(request.allowedIds.size)
            request.allowedIds.sorted().forEach(out::writeUTF)
            out.writeUTF(request.targetId.orEmpty())
        } }.toByteArray().also { require(it.size <= MAX_BODY) }
    }

    fun decode(bytes: ByteArray): DiscoveryRequest {
        if (bytes.size > MAX_BODY) throw IOException("Слишком большой запрос координатору")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Неподдерживаемый протокол координатора")
            val operation = DiscoveryRequest.Operation.entries.getOrNull(input.readUnsignedByte())
                ?: throw IOException("Неизвестная операция координатора")
            val nonce = ByteArray(32).also(input::readFully)
            val signer = input.readUTF()
            val ready = input.readBoolean()
            val relay = input.readUTF().ifEmpty { null }
            val addressCount = input.readUnsignedByte()
            if (addressCount > MAX_ADDRESSES) throw IOException("Слишком много адресов")
            val addresses = List(addressCount) { input.readUTF() }
            val friendCount = input.readUnsignedShort()
            if (friendCount > MAX_FRIENDS) throw IOException("Слишком много друзей")
            val friends = List(friendCount) { input.readUTF() }.toSet()
            if (friends.size != friendCount) throw IOException("Повтор ID друга")
            val target = input.readUTF().ifEmpty { null }
            if (input.available() != 0) throw IOException("Лишние данные запроса")
            DiscoveryRequest(operation, nonce, signer, relay, addresses, friends, ready, target).also(::validate)
        }
    }

    fun verify(payload: ByteArray, signature: ByteArray): DiscoveryRequest {
        val request = decode(payload)
        if (signature.size != 64) throw IOException("Неверная длина подписи")
        try {
            EndpointId.fromString(request.signerId).use { id ->
                Signature.fromBytes(signature).use { id.verify(payload, it) }
            }
        } catch (error: Exception) { throw IOException("Подпись ID не подтверждена", error) }
        return request
    }

    fun signed(payload: ByteArray, signature: ByteArray): ByteArray {
        require(payload.size <= MAX_BODY && signature.size == 64)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(payload.size)
            out.write(payload)
            out.write(signature)
        } }.toByteArray()
    }

    fun decodeSigned(bytes: ByteArray): Pair<ByteArray, ByteArray> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (bytes.size > MAX_BODY + 68) throw IOException("Слишком большой подписанный запрос")
        val length = input.readInt()
        if (length !in 1..MAX_BODY || bytes.size != length + 68) throw IOException("Некорректная длина запроса")
        val payload = ByteArray(length).also(input::readFully)
        val signature = ByteArray(64).also(input::readFully)
        payload to signature
    }

    fun encodeResult(result: DiscoveryResult): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(result.presence.ordinal)
            val record = result.record
            out.writeBoolean(record != null)
            if (record != null) {
                out.writeUTF(record.id)
                out.writeUTF(record.relay.orEmpty())
                out.writeByte(record.directAddresses.size)
                record.directAddresses.forEach(out::writeUTF)
                out.writeBoolean(record.ready)
                out.writeLong(record.lastSeenMs)
            }
        }
    }.toByteArray()

    fun decodeResult(bytes: ByteArray): DiscoveryResult = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (bytes.size > MAX_BODY || input.readInt() != MAGIC) throw IOException("Некорректный ответ координатора")
        val presence = Presence.entries.getOrNull(input.readUnsignedByte()) ?: throw IOException("Неизвестное состояние участника")
        val record = if (!input.readBoolean()) null else {
            val id = input.readUTF().also(::validateId)
            val relay = input.readUTF().ifEmpty { null }?.also(::validateRelay)
            val count = input.readUnsignedByte()
            if (count > MAX_ADDRESSES) throw IOException("Слишком много адресов")
            val addresses = List(count) { input.readUTF().also(::validateAddress) }
            DiscoveryRecord(id, relay, addresses, emptySet(), input.readBoolean(), input.readLong())
        }
        if (input.available() != 0 || presence == Presence.UNKNOWN && record != null) throw IOException("Некорректный ответ координатора")
        DiscoveryResult(presence, record)
    }

    private fun validate(request: DiscoveryRequest) {
        require(request.nonce.size == 32) { "Неверный nonce координатора" }
        validateId(request.signerId)
        request.relay?.let(::validateRelay)
        require(request.directAddresses.size <= MAX_ADDRESSES) { "Слишком много адресов" }
        request.directAddresses.forEach(::validateAddress)
        require(request.allowedIds.size <= MAX_FRIENDS) { "Слишком много друзей" }
        request.allowedIds.forEach(::validateId)
        request.targetId?.let(::validateId)
        when (request.operation) {
            DiscoveryRequest.Operation.REGISTER -> require(request.targetId == null) { "Регистрация не содержит цель" }
            DiscoveryRequest.Operation.LOOKUP -> require(request.targetId != null && request.relay == null &&
                request.directAddresses.isEmpty() && request.allowedIds.isEmpty() && !request.ready) { "Некорректный поиск" }
        }
    }

    private fun validateId(raw: String) {
        try { EndpointId.fromString(raw).use { require(it.toString() == raw) } }
        catch (error: Exception) { throw IOException("Некорректный ID участника", error) }
    }

    private fun validateAddress(raw: String) {
        if (raw.length !in 3..256 || raw.any { it.isISOControl() || it.isWhitespace() }) throw IOException("Некорректный адрес")
    }
}
