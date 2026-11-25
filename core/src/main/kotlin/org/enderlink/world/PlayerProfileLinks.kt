package org.enderlink.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

enum class PlayerProfileChoice { OWNER, LAN }

/** Both profiles exist, but their last synchronized state does not identify one newer profile. */
class PlayerProfileConflictException(val owner: UUID, val guest: UUID) : IOException(
    "Состояния игрока $owner и LAN-гостя $guest различаются. Выберите профиль владельца или LAN-гостя явно",
)

/** Versioned save metadata, transferred with the world and ignored by Minecraft. */
internal object PlayerProfileLinks {
    const val FILE_NAME = ".enderlink-player-links"
    private const val MAGIC = 0x454c5031 // ELP1
    private const val MAX_LINKS = 1024
    private const val MAX_BYTES = 256 * 1024
    private val hashPattern = Regex("[0-9a-f]{64}")

    data class Link(val guest: UUID, val fingerprint: String)

    fun read(world: Path): Map<UUID, Link> {
        val path = world.resolve(FILE_NAME)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return emptyMap()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Ожидается обычный файл: $path")
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw IOException("Слишком большой файл связей игроков")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Неизвестный формат связей игроков")
            val count = input.readInt()
            if (count !in 0..MAX_LINKS) throw IOException("Некорректное число связей игроков")
            val links = linkedMapOf<UUID, Link>()
            repeat(count) {
                val owner = readUuid(input)
                val guest = readUuid(input)
                val fingerprint = input.readUTF()
                if (owner == guest || !hashPattern.matches(fingerprint) || owner in links ||
                    links.values.any { it.guest == guest }) throw IOException("Некорректная связь игроков")
                links[owner] = Link(guest, fingerprint)
            }
            if (input.read() != -1) throw IOException("Лишние данные в файле связей игроков")
            return links
        }
    }

    fun record(world: Path, owner: UUID, guest: UUID, fingerprint: String) {
        require(owner != guest && hashPattern.matches(fingerprint))
        val links = read(world).toMutableMap()
        if (links.any { (id, link) -> id != owner && link.guest == guest }) {
            throw IOException("Этот LAN-профиль уже связан с другим владельцем: $guest")
        }
        links[owner] = Link(guest, fingerprint)
        if (links.size > MAX_LINKS) throw IOException("Слишком много связей игроков")
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(links.size)
                for ((id, link) in links.entries.sortedBy { it.key.toString() }) {
                    output.writeUTF(id.toString())
                    output.writeUTF(link.guest.toString())
                    output.writeUTF(link.fingerprint)
                }
            }
        }.toByteArray()
        val temporary = Files.createTempFile(world, ".enderlink-links-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, world.resolve(FILE_NAME), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun readUuid(input: DataInputStream): UUID {
        val raw = input.readUTF()
        val uuid = try { UUID.fromString(raw) } catch (error: IllegalArgumentException) {
            throw IOException("Некорректный UUID в связях игроков", error)
        }
        if (uuid.toString() != raw) throw IOException("Некорректный UUID в связях игроков")
        return uuid
    }
}
