package org.enderlink.irohtest

import computer.iroh.EndpointId
import computer.iroh.SecretKey
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Один запущенный процесс на профиль; секретный ключ никогда не выводится в консоль. */
internal class Profile private constructor(
    val directory: Path,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    val key: ByteArray = loadKey()
    val id: String = SecretKey.fromBytes(key).use { secret -> secret.public().use { it.toString() } }
    private val friendsPath = directory.resolve("friends.properties")
    private val relaysPath = directory.resolve("relay-hints.properties")
    private val friends = loadProperties(friendsPath)
    private val relays = loadProperties(relaysPath)

    private fun loadProperties(path: Path): Properties = Properties().apply {
        if (Files.exists(path)) {
            Files.newInputStream(path).use {
                val bytes = it.readNBytes(1024 * 1024 + 1)
                require(bytes.size <= 1024 * 1024) { "Слишком большой файл профиля: ${path.fileName}" }
                bytes.inputStream().use(::load)
            }
        }
    }

    fun friends(): Map<String, String> = friends.stringPropertyNames().sorted().associateWith { friends.getProperty(it) }

    fun add(name: String, rawId: String) {
        require(name.matches(Regex("[\\p{L}\\p{N}_-]{1,40}"))) {
            "Имя друга: 1–40 букв, цифр, '_' или '-'"
        }
        val friendId = EndpointId.fromString(rawId).use { it.toString() }
        require(friendId != id) { "Это ваш собственный ID" }
        require(friends.getProperty(name).let { it == null || it == friendId }) {
            "Имя уже занято другим ID. Сначала выполните remove $name"
        }
        val updated = Properties().apply { putAll(friends); setProperty(name, friendId) }
        saveFriends(updated)
    }

    fun remove(name: String) {
        val removedId = friendId(name)
        saveFriends(Properties().apply { putAll(friends); remove(name) })
        if (friendName(removedId) == null && relays.containsKey(removedId)) {
            saveRelays(Properties().apply { putAll(relays); remove(removedId) })
        }
    }

    fun friendId(name: String): String = requireNotNull(friends.getProperty(name)) {
        "Друг не найден: $name. Добавьте его командой add <имя> <ID>"
    }

    fun friendName(remoteId: String): String? = friends().entries.firstOrNull { it.value == remoteId }?.key

    fun relayFor(remoteId: String): String? = relays.getProperty(remoteId)?.takeIf { raw ->
        runCatching { validateRelay(raw); true }.getOrDefault(false)
    }

    /** Подсказка адреса не заменяет проверку постоянного ID при QUIC-handshake. */
    fun rememberRelay(remoteId: String, relay: String): Boolean {
        require(friendName(remoteId) != null) { "Нельзя сохранить relay неизвестного участника" }
        validateRelay(relay)
        if (relays.getProperty(remoteId) == relay) return false
        saveRelays(Properties().apply { putAll(relays); setProperty(remoteId, relay) })
        return true
    }

    private fun saveRelays(updated: Properties) {
        val bytes = ByteArrayOutputStream().also { updated.store(it, "EnderLink relay hints; authenticated IDs remain in friends.properties") }.toByteArray()
        writeAtomic(relaysPath, bytes)
        relays.clear()
        relays.putAll(updated)
    }

    private fun saveFriends(updated: Properties) {
        val bytes = ByteArrayOutputStream().also { updated.store(it, "EnderLink iroh-test friends") }.toByteArray()
        writeAtomic(friendsPath, bytes)
        friends.clear()
        friends.putAll(updated)
    }

    private fun loadKey(): ByteArray {
        val path = directory.resolve("secret.key")
        if (!Files.exists(path)) {
            val bytes = SecretKey.generate().use { it.toBytes() }
            writeAtomic(path, bytes)
            return bytes
        }
        return Files.newInputStream(path).use { it.readNBytes(33) }.also {
            require(it.size == 32) { "Повреждён secret.key; восстановите профиль из копии. Новый ID не создан." }
        }
    }

    override fun close() {
        try { lock.release() } finally { lockChannel.close(); key.fill(0) }
    }

    companion object {
        fun open(path: Path): Profile {
            Files.createDirectories(path)
            val directory = path.toRealPath()
            val channel = FileChannel.open(directory.resolve("profile.lock"), CREATE, WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw IOException("Профиль уже используется. Закройте другой процесс или задайте другой --profile")
                try { return Profile(directory, channel, lock) }
                catch (e: Throwable) { lock.release(); throw e }
            } catch (e: Throwable) { channel.close(); throw e }
        }

        private fun writeAtomic(path: Path, bytes: ByteArray) {
            val attrs = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else emptyArray()
            val pending = Files.createTempFile(path.parent, ".pending-", ".tmp", *attrs)
            try {
                FileChannel.open(pending, WRITE).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try { Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(pending, path, REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(pending) }
        }
    }
}

internal fun validateRelay(raw: String) {
    val uri = URI(raw)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Relay должен быть HTTPS-адресом из вывода listen"
    }
}
