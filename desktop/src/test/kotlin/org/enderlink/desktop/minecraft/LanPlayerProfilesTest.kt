package org.enderlink.desktop.minecraft

import org.enderlink.world.PlayerDataInspector
import org.enderlink.world.WorldRepository
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanPlayerProfilesTest {
    @TempDir lateinit var root: Path
    private val hostA = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val hostB = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
    private val guestA = PlayerDataInspector.offlineUuid("PlayerA")
    private val guestB = PlayerDataInspector.offlineUuid("PlayerB")

    @Test
    fun `modern world preserves both players across host and guest roles`() = roundTrip(modern = true)

    @Test
    fun `legacy world preserves both players across host and guest roles`() = roundTrip(modern = false)

    private fun roundTrip(modern: Boolean) {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, modern, hostA, "minecraft:diamond")
        writeProfile(source, modern, hostA, "minecraft:diamond")
        writeProfile(source, modern, guestA, "minecraft:apple")
        writeProfile(source, modern, guestB, "minecraft:emerald")
        val originalLevel = Files.readAllBytes(source.resolve("level.dat"))
        val repo = WorldRepository(root.resolve("repository"))
        val first = repo.publishSingleplayer(source, hostA, guestA)
        assertProfile(repo.workingDirectory, modern, guestA, "minecraft:diamond")
        assertProfile(source, modern, guestA, "minecraft:apple")
        assertContentEquals(originalLevel, Files.readAllBytes(source.resolve("level.dat")))

        val saveB = MinecraftSaves.install(repo, root.resolve("saves-b"), "World", owner = hostB, guest = guestB).directory
        assertProfile(saveB, modern, hostB, "minecraft:emerald")
        // B plays as host while A joins the LAN server under their offline UUID.
        writeLevel(saveB, modern, hostB, "minecraft:iron_ingot")
        writeProfile(saveB, modern, hostB, "minecraft:iron_ingot")
        writeProfile(saveB, modern, guestA, "minecraft:gold_ingot")
        val second = repo.publishSingleplayer(saveB, hostB, guestB)
        assertEquals(first.versionId, second.parentVersionId)
        assertProfile(repo.workingDirectory, modern, guestB, "minecraft:iron_ingot")
        assertProfile(repo.workingDirectory, modern, guestA, "minecraft:gold_ingot")

        val saveA = MinecraftSaves.install(repo, root.resolve("saves-a"), "World", owner = hostA, guest = guestA).directory
        assertProfile(saveA, modern, hostA, "minecraft:gold_ingot")
        if (!modern) assertContentEquals(player(hostA, "minecraft:gold_ingot"), PlayerDataInspector.extractLegacyPlayer(saveA))
        writeLevel(saveA, modern, hostA, "minecraft:netherite_ingot")
        writeProfile(saveA, modern, hostA, "minecraft:netherite_ingot")
        writeProfile(saveA, modern, guestB, "minecraft:copper_ingot")
        repo.publishSingleplayer(saveA, hostA, guestA)
        assertProfile(repo.workingDirectory, modern, guestA, "minecraft:netherite_ingot")
        val nextB = MinecraftSaves.install(repo, root.resolve("next-saves-b"), "World", owner = hostB, guest = guestB).directory
        assertProfile(nextB, modern, hostB, "minecraft:copper_ingot")
        assertContentEquals(originalLevel, Files.readAllBytes(source.resolve("level.dat")))
        repo.verifyCurrent()
    }

    @Test
    fun `changing LAN identity reapplies same version and survives interrupted install`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, true, hostB, "minecraft:emerald")
        writeProfile(source, true, hostA, "minecraft:diamond")
        writeProfile(source, true, guestA, "minecraft:gold_ingot")
        val repo = WorldRepository(root.resolve("repository"))
        repo.publish(source)
        val saves = root.resolve("saves")
        val target = MinecraftSaves.install(repo, saves, "World", owner = hostA).directory
        assertProfile(target, true, hostA, "minecraft:diamond")
        val updated = MinecraftSaves.install(repo, saves, "World", owner = hostA, guest = guestA)
        assertTrue(updated.replacedFiles > 0)
        assertProfile(target, true, hostA, "minecraft:gold_ingot")
        assertEquals(ManagedSaveState.UNCHANGED, MinecraftSaves.inspectManaged(repo, saves).single().state)
        Files.move(target, saves.resolve(".enderlink-sync/World.backup"))
        MinecraftSaves.install(repo, saves, "World", owner = hostA, guest = guestA)
        assertProfile(target, true, hostA, "minecraft:gold_ingot")
        writeProfile(target, true, hostA, "minecraft:apple")
        assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World", owner = hostA, guest = guestA) }
        assertProfile(target, true, hostA, "minecraft:apple")
    }

    @Test
    fun `corrupt LAN profile leaves installed world and active version intact`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, true, hostB, "minecraft:emerald")
        writeProfile(source, true, hostA, "minecraft:diamond")
        val repo = WorldRepository(root.resolve("repository"))
        repo.publish(source)
        val saves = root.resolve("saves")
        val target = MinecraftSaves.install(repo, saves, "World", owner = hostA).directory
        Files.writeString(dataPath(source, true, guestA), "broken")
        val incoming = repo.publish(source)
        assertFailsWith<IOException> { MinecraftSaves.install(repo, saves, "World", owner = hostA, guest = guestA) }
        assertProfile(target, true, hostA, "minecraft:diamond")
        assertEquals(incoming.versionId, repo.current()?.versionId)
        repo.verifyCurrent()
    }

    @Test
    fun `LAN publication rejects a UUID that belongs to a different singleplayer owner`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, true, hostA, "minecraft:diamond")
        writeProfile(source, true, hostA, "minecraft:diamond")
        val repo = WorldRepository(root.resolve("repository"))
        val first = repo.publish(source)
        val error = assertFailsWith<IOException> { repo.publishSingleplayer(source, hostB, guestB) }
        assertTrue(error.message.orEmpty().contains(hostA.toString()))
        assertEquals(first.versionId, repo.current()?.versionId)
        assertProfile(source, true, hostA, "minecraft:diamond")
    }

    @Test
    fun `offline UUID is case sensitive and matches Minecraft guest identity`() {
        assertEquals(UUID.fromString("19cd25e7-f2a9-37b4-a3d6-8fffdb8aa5d1"), PlayerDataInspector.offlineUuid("PlayerA"))
        assertTrue(PlayerDataInspector.offlineUuid("PlayerA") != PlayerDataInspector.offlineUuid("playera"))
        assertFailsWith<IllegalArgumentException> { PlayerDataInspector.offlineUuid("../Player") }
    }

    @Test
    fun `missing LAN profile uses existing account profile`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, true, hostB, "minecraft:emerald")
        writeProfile(source, true, hostA, "minecraft:diamond")
        val repo = WorldRepository(root.resolve("repository"))
        repo.publish(source)
        val installed = MinecraftSaves.install(repo, root.resolve("saves"), "World", owner = hostA, guest = guestA)
        assertProfile(installed.directory, true, hostA, "minecraft:diamond")
    }

    @Test
    fun `missing host statistics clear stale linked statistics only in publication copy`() {
        val source = Files.createDirectory(root.resolve("source"))
        writeLevel(source, true, hostA, "minecraft:diamond")
        writeProfile(source, true, hostA, "minecraft:diamond")
        writeProfile(source, true, guestA, "minecraft:apple")
        Files.delete(source.resolve("players/stats/$hostA.json"))
        Files.delete(source.resolve("players/advancements/$hostA.json"))
        val repo = WorldRepository(root.resolve("repository"))
        repo.publishSingleplayer(source, hostA, guestA)
        assertTrue(Files.notExists(repo.workingDirectory.resolve("players/stats/$guestA.json")))
        assertTrue(Files.notExists(repo.workingDirectory.resolve("players/advancements/$guestA.json")))
        assertProfile(source, true, guestA, "minecraft:apple")
    }

    private fun dataPath(world: Path, modern: Boolean, owner: UUID): Path =
        world.resolve(if (modern) "players/data/$owner.dat" else "playerdata/$owner.dat")

    private fun writeProfile(world: Path, modern: Boolean, owner: UUID, item: String) {
        val path = dataPath(world, modern, owner)
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).use { it.write(player(owner, item)) }
        for (kind in listOf("stats", "advancements")) {
            val directory = world.resolve(if (modern) "players/$kind" else kind)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("$owner.json"), "{\"test\":\"$item\"}")
        }
    }

    private fun assertProfile(world: Path, modern: Boolean, owner: UUID, item: String) {
        val actual = GZIPInputStream(Files.newInputStream(dataPath(world, modern, owner))).use { it.readAllBytes() }
        assertContentEquals(player(owner, item), actual)
        for (kind in listOf("stats", "advancements")) {
            val directory = world.resolve(if (modern) "players/$kind" else kind)
            assertEquals("{\"test\":\"$item\"}", Files.readString(directory.resolve("$owner.json")))
        }
    }

    private fun player(owner: UUID, item: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte(10); output.writeUTF("")
            output.writeUuid("UUID", owner)
            output.writeByte(9); output.writeUTF("Inventory"); output.writeByte(10); output.writeInt(1)
            output.writeByte(8); output.writeUTF("id"); output.writeUTF(item); output.writeByte(0)
            output.writeByte(9); output.writeUTF("EnderItems"); output.writeByte(10); output.writeInt(1)
            output.writeByte(8); output.writeUTF("id"); output.writeUTF("minecraft:ender_pearl"); output.writeByte(0)
            output.writeByte(9); output.writeUTF("Pos"); output.writeByte(6); output.writeInt(3)
            output.writeDouble(12.5); output.writeDouble(64.0); output.writeDouble(-34.5)
            output.writeByte(3); output.writeUTF("XpLevel"); output.writeInt(12)
            output.writeByte(0)
        }
    }.toByteArray()

    private fun writeLevel(world: Path, modern: Boolean, owner: UUID, item: String) {
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))).use { output ->
            output.writeByte(10); output.writeUTF("")
            output.writeByte(10); output.writeUTF("Data")
            if (modern) output.writeUuid("singleplayer_uuid", owner)
            else {
                output.writeByte(10); output.writeUTF("Player")
                val player = player(owner, item)
                output.write(player, 3, player.size - 3)
            }
            output.writeByte(0); output.writeByte(0)
        }
    }

    private fun DataOutputStream.writeUuid(name: String, owner: UUID) {
        writeByte(11); writeUTF(name); writeInt(4)
        writeLong(owner.mostSignificantBits); writeLong(owner.leastSignificantBits)
    }
}
