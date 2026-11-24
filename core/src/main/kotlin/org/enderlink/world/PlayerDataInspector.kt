package org.enderlink.world

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.UUID
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Inspects player NBT and prepares private copies for a chosen singleplayer owner. */
object PlayerDataInspector {
    data class Result(
        val legacyEmbeddedPlayer: Boolean,
        val singleplayerUuid: Boolean,
        val legacyPlayerFiles: Int,
        val modernPlayerFiles: Int,
        val levelReadable: Boolean,
    )

    fun inspect(world: Path): Result {
        val legacy = countPlayers(world.resolve("playerdata"))
        val modern = countPlayers(world.resolve("players/data"))
        val level = world.resolve("level.dat")
        if (!Files.isRegularFile(level, NOFOLLOW_LINKS)) return Result(false, false, legacy, modern, false)
        return try {
            Files.newInputStream(level, NOFOLLOW_LINKS).use { file ->
                DataInputStream(LimitedInputStream(GZIPInputStream(file))).use { input ->
                    if (input.readUnsignedByte() != 10) throw IOException("level.dat: ожидается корневой compound")
                    readName(input)
                    var embedded = false
                    var uuid = false
                    visitCompound(input, 0) { name, type ->
                        if (name == "Data" && type == 10) {
                            visitCompound(input, 1) { child, childType ->
                                if (child == "Player" && childType == 10) embedded = true
                                if (child == "singleplayer_uuid" && childType == 11) uuid = true
                                skipPayload(input, childType, 2)
                            }
                        } else skipPayload(input, type, 1)
                    }
                    Result(embedded, uuid, legacy, modern, true)
                }
            }
        } catch (_: IOException) {
            Result(false, false, legacy, modern, false)
        }
    }

    /** The complete old Data.Player compound as a standalone, uncompressed NBT root. */
    fun extractLegacyPlayer(world: Path): ByteArray {
        val level = world.resolve("level.dat")
        if (!Files.isRegularFile(level, NOFOLLOW_LINKS)) throw IOException("Не найден level.dat")
        val raw = Files.newInputStream(level, NOFOLLOW_LINKS).use { file ->
            LimitedInputStream(GZIPInputStream(file)).use { it.readAllBytes() }
        }
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (input.readUnsignedByte() != 10) throw IOException("Некорректный корень level.dat")
        readName(input)
        var player: ByteArray? = null
        var modern = false
        visitCompound(input, 0) { name, type ->
            if (name == "Data" && type == 10) {
                visitCompound(input, 1) { child, childType ->
                    if (child == "singleplayer_uuid") modern = true
                    if (child == "Player" && childType == 10) {
                        if (player != null) throw IOException("В level.dat несколько профилей владельца")
                        val start = raw.size - input.available()
                        skipPayload(input, childType, 2)
                        val end = raw.size - input.available()
                        player = byteArrayOf(10, 0, 0) + raw.copyOfRange(start, end)
                    } else skipPayload(input, childType, 2)
                }
            } else skipPayload(input, type, 1)
        }
        if (modern) throw IOException("Этот мир использует отдельный файл игрока; перенос из level.dat не нужен")
        return player ?: throw IOException("В level.dat не найдены данные владельца Data.Player")
    }

    /** In a closed legacy singleplayer save, Data.Player is authoritative for the explicitly selected owner. */
    fun captureLegacyOwner(world: Path, owner: UUID) {
        val state = inspect(world)
        if (!state.levelReadable) throw IOException("Не удалось проверить level.dat")
        if (!state.legacyEmbeddedPlayer) return
        val player = extractLegacyPlayer(world)
        val directory = world.resolve("playerdata")
        if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw IOException("playerdata должен быть обычным каталогом")
        }
        Files.createDirectories(directory)
        val target = directory.resolve("$owner.dat")
        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            throw IOException("Файл игрока должен быть обычным файлом: $target")
        }
        writeGzip(target, player)
    }

    /** Prepares a private save for the chosen account; published world bytes stay unchanged. */
    fun prepareSingleplayerOwner(world: Path, owner: UUID) {
        val state = inspect(world)
        if (!state.levelReadable) throw IOException("Не удалось проверить level.dat")
        val raw = readLevel(world)
        val playerTag = findDataTag(raw, "Player")
        val uuidTag = findDataTag(raw, "singleplayer_uuid")
        if (playerTag != null && uuidTag != null) throw IOException("В level.dat одновременно есть Player и singleplayer_uuid")
        when {
            uuidTag != null -> {
                if (uuidTag.type != 11) throw IOException("Некорректный singleplayer_uuid")
                if (java.nio.ByteBuffer.wrap(raw, uuidTag.payloadStart, 4).int != 4) {
                    throw IOException("Некорректная длина singleplayer_uuid")
                }
                val target = world.resolve("players/data/$owner.dat")
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) throw IOException("Файл игрока должен быть обычным файлом: $target")
                    playerPayloadStart(readGzip(target))
                }
                val replacement = ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { output ->
                        output.writeByte(11); output.writeUTF("singleplayer_uuid"); output.writeInt(4)
                        output.writeInt((owner.mostSignificantBits shr 32).toInt())
                        output.writeInt(owner.mostSignificantBits.toInt())
                        output.writeInt((owner.leastSignificantBits shr 32).toInt())
                        output.writeInt(owner.leastSignificantBits.toInt())
                    }
                }.toByteArray()
                writeGzip(world.resolve("level.dat"), raw.splice(uuidTag, replacement))
            }
            playerTag != null -> {
                if (playerTag.type != 10) throw IOException("Некорректный Data.Player")
                val embedded = extractLegacyPlayer(world)
                val playerDirectory = world.resolve("playerdata")
                if (!Files.isDirectory(playerDirectory, NOFOLLOW_LINKS)) {
                    throw IOException("Нет файла прежнего владельца. Отправитель должен опубликовать закрытый мир с UUID владельца")
                }
                val ownerRecorded = Files.newDirectoryStream(playerDirectory, "*.dat").use { files ->
                    files.asSequence().any { file ->
                        val name = file.fileName.toString().removeSuffix(".dat")
                        val uuid = runCatching { UUID.fromString(name) }.getOrNull()
                        uuid?.toString() == name && Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                            runCatching { readGzip(file).contentEquals(embedded) }.getOrDefault(false)
                    }
                }
                if (!ownerRecorded) {
                    throw IOException("Не найдена сохранённая запись прежнего владельца. Отправитель должен опубликовать закрытый мир с UUID владельца")
                }
                val target = world.resolve("playerdata/$owner.dat")
                val replacement = if (Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                    val bytes = readGzip(target)
                    val payloadStart = playerPayloadStart(bytes)
                    ByteArrayOutputStream().also { stream ->
                        DataOutputStream(stream).use { output ->
                            output.writeByte(10); output.writeUTF("Player")
                            output.write(bytes, payloadStart, bytes.size - payloadStart)
                        }
                    }.toByteArray()
                } else {
                    if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Файл игрока должен быть обычным файлом: $target")
                    byteArrayOf()
                }
                writeGzip(world.resolve("level.dat"), raw.splice(playerTag, replacement))
            }
        }
    }

    private data class TagRange(val start: Int, val end: Int, val type: Int, val payloadStart: Int)

    private fun findDataTag(raw: ByteArray, wanted: String): TagRange? {
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (input.readUnsignedByte() != 10) throw IOException("Некорректный корень level.dat")
        readName(input)
        var found: TagRange? = null
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) break
            val name = readName(input)
            if (name == "Data" && type == 10) {
                while (true) {
                    val start = raw.size - input.available()
                    val childType = input.readUnsignedByte()
                    if (childType == 0) break
                    val child = readName(input)
                    val payloadStart = raw.size - input.available()
                    skipPayload(input, childType, 2)
                    if (child == wanted) {
                        if (found != null) throw IOException("В level.dat несколько тегов $wanted")
                        found = TagRange(start, raw.size - input.available(), childType, payloadStart)
                    }
                }
            } else skipPayload(input, type, 1)
        }
        return found
    }

    private fun ByteArray.splice(range: TagRange, replacement: ByteArray): ByteArray =
        copyOfRange(0, range.start) + replacement + copyOfRange(range.end, size)

    private fun playerPayloadStart(raw: ByteArray): Int {
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (input.readUnsignedByte() != 10) throw IOException("Файл игрока не содержит корневой compound")
        readName(input)
        val start = raw.size - input.available()
        skipPayload(input, 10, 0)
        if (input.available() != 0) throw IOException("После данных игрока есть лишние байты")
        return start
    }

    private fun readLevel(world: Path): ByteArray = readGzip(world.resolve("level.dat"))

    private fun readGzip(path: Path): ByteArray = Files.newInputStream(path, NOFOLLOW_LINKS).use { file ->
        LimitedInputStream(GZIPInputStream(file)).use { it.readAllBytes() }
    }

    private fun writeGzip(path: Path, raw: ByteArray) {
        val temp = Files.createTempFile(path.parent, ".enderlink-player-", ".tmp")
        try {
            GZIPOutputStream(Files.newOutputStream(temp)).use { it.write(raw) }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun countPlayers(directory: Path): Int {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return 0
        Files.newDirectoryStream(directory, "*.dat").use { files ->
            return files.count { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        }
    }

    private fun visitCompound(input: DataInputStream, depth: Int, visit: (String, Int) -> Unit) {
        if (depth > 64) throw IOException("NBT слишком глубоко вложен")
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) return
            visit(readName(input), type)
        }
    }

    private fun readName(input: DataInputStream): String {
        val size = input.readUnsignedShort()
        return String(input.readNBytes(size).also {
            if (it.size != size) throw IOException("Оборванное имя NBT")
        }, Charsets.UTF_8)
    }

    private fun skipPayload(input: DataInputStream, type: Int, depth: Int) {
        if (depth > 64) throw IOException("NBT слишком глубоко вложен")
        when (type) {
            1 -> input.skipNBytes(1)
            2 -> input.skipNBytes(2)
            3, 5 -> input.skipNBytes(4)
            4, 6 -> input.skipNBytes(8)
            7 -> input.skipNBytes(arrayBytes(input, 1))
            8 -> input.skipNBytes(input.readUnsignedShort().toLong())
            9 -> {
                val elementType = input.readUnsignedByte()
                val count = input.readInt()
                if (count !in 0..1_000_000 || (count > 0 && elementType !in 1..12)) throw IOException("Некорректный список NBT")
                repeat(count) { skipPayload(input, elementType, depth + 1) }
            }
            10 -> visitCompound(input, depth) { _, childType -> skipPayload(input, childType, depth + 1) }
            11 -> input.skipNBytes(arrayBytes(input, 4))
            12 -> input.skipNBytes(arrayBytes(input, 8))
            else -> throw IOException("Неизвестный тип NBT: $type")
        }
    }

    private fun arrayBytes(input: DataInputStream, elementSize: Int): Long {
        val count = input.readInt()
        if (count < 0 || count.toLong() * elementSize > MAX_LEVEL_BYTES) throw IOException("Слишком большой массив NBT")
        return count.toLong() * elementSize
    }

    private class LimitedInputStream(input: InputStream) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = super.read(bytes, off, len).also { if (it > 0) add(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
        private fun add(bytes: Long) {
            count += bytes
            if (count > MAX_LEVEL_BYTES) throw IOException("level.dat слишком большой после распаковки")
        }
    }

    private const val MAX_LEVEL_BYTES = 32L * 1024 * 1024
}
