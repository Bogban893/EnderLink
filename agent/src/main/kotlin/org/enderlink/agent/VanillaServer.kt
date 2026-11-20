package org.enderlink.agent

import org.enderlink.world.WorldRepository
import org.enderlink.world.WorldScanner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Один запуск сервера использует отдельную проверенную копию опубликованного мира. */
class VanillaServer(
    private val repository: WorldRepository,
    serverDirectory: Path,
    private val requestedVersion: String? = null,
) {
    private val root = serverDirectory.toAbsolutePath().normalize()
    val worldDirectory: Path = root.resolve("world")
    private var activeVersion: String? = null
    val versionId: String get() = activeVersion ?: throw IOException("Мир ещё не подготовлен")
    private var process: Process? = null

    fun prepare() {
        if (process != null || activeVersion != null) throw IOException("Мир уже подготовлен")
        if (Files.isSymbolicLink(root)) throw IOException("Каталог сервера не должен быть ссылкой")
        val repositoryRoot = repository.workingDirectory.parent
        if (root.startsWith(repositoryRoot) || repositoryRoot.startsWith(root)) {
            throw IOException("Каталог сервера должен быть отдельно от хранилища версий")
        }
        Files.createDirectories(root)
        if (Files.exists(worldDirectory, NOFOLLOW_LINKS)) throw IOException("Каталог мира уже существует: $worldDirectory")
        val eula = root.resolve("eula.txt")
        if (!Files.isRegularFile(eula, NOFOLLOW_LINKS) || !property(eula, "eula").equals("true", ignoreCase = true)) {
            throw IOException("В каталоге сервера нужен eula.txt с eula=true после принятия лицензии пользователем")
        }
        val properties = root.resolve("server.properties")
        if (Files.exists(properties, NOFOLLOW_LINKS) &&
            (!Files.isRegularFile(properties, NOFOLLOW_LINKS) || property(properties, "level-name")?.let { it != "world" } == true)) {
            throw IOException("server.properties должен использовать level-name=world")
        }
        val manifest = repository.current() ?: throw IOException("Сначала опубликуйте версию мира")
        if (requestedVersion != null && manifest.versionId != requestedVersion) {
            throw IOException("Активна версия ${manifest.versionId}; выберите нужную через world-checkout")
        }
        repository.verifyCurrent()
        val source = repository.workingDirectory
        val stage = Files.createTempDirectory(root, ".enderlink-world-")
        try {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isDirectory) throw IOException("Необычный каталог мира: $dir")
                    if (dir != source) Files.createDirectory(stage.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) throw IOException("Ссылки в мире не поддерживаются: $file")
                    Files.copy(file, stage.resolve(source.relativize(file)), NOFOLLOW_LINKS)
                    return FileVisitResult.CONTINUE
                }
            })
            if (WorldScanner.scan(stage, manifest.worldId, manifest.parentVersionId, manifest.formatVersion) != manifest) {
                throw IOException("Подготовленная копия не совпадает с версией мира")
            }
            Files.move(stage, worldDirectory)
            activeVersion = manifest.versionId
        } finally {
            if (Files.exists(stage, NOFOLLOW_LINKS)) deleteTree(stage)
        }
    }

    fun start(command: List<String>) {
        if (activeVersion == null) throw IOException("Сначала подготовьте мир")
        if (process != null) throw IOException("Сервер уже запускался")
        require(command.isNotEmpty()) { "Не указана команда запуска" }
        val started = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
        process = started
        val log = root.resolve("enderlink-server.log")
        Thread({
            try {
                Files.newBufferedWriter(log, CREATE, APPEND).use { output ->
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.appendLine(line)
                            output.flush()
                            println(line)
                        }
                    }
                }
            } catch (e: IOException) {
                System.err.println("Не удалось записать журнал сервера: ${e.message}")
            }
        }, "enderlink-server-log").apply { isDaemon = true; start() }
        Thread({
            val code = started.waitFor()
            println("Сервер завершился с кодом $code. Журнал: $log")
        }, "enderlink-server-exit").apply { isDaemon = true; start() }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun status(): String = when (val running = process) {
        null -> "Сервер не запущен"
        else -> if (running.isAlive) "Сервер работает (PID ${running.pid()}); версия $versionId"
            else "Сервер остановлен (код ${running.exitValue()}); журнал ${root.resolve("enderlink-server.log")}"
    }

    fun save() = sendCommand("save-all flush")

    fun stopAndPublish(): String {
        val running = process ?: throw IOException("Сервер не запущен")
        if (!running.isAlive) throw IOException("Сервер завершился без штатной остановки: ${status()}")
        save()
        sendCommand("stop")
        if (!running.waitFor(120, TimeUnit.SECONDS)) {
            throw IOException("Сервер не остановился за 120 секунд; мир оставлен в $worldDirectory")
        }
        if (running.exitValue() != 0) throw IOException("Сервер завершился с кодом ${running.exitValue()}; мир оставлен в $worldDirectory")
        if (repository.current()?.versionId != versionId) {
            throw IOException("Активная версия репозитория изменилась; мир оставлен в $worldDirectory")
        }
        return repository.publish(worldDirectory).versionId
    }

    private fun sendCommand(command: String) {
        val running = process ?: throw IOException("Сервер не запущен")
        if (!running.isAlive) throw IOException("Сервер уже завершился: ${status()}")
        try {
            running.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
            running.outputStream.flush()
        } catch (e: IOException) { throw IOException("Не удалось отправить команду серверу: $command", e) }
    }

    private fun property(path: Path, name: String): String? = Files.readAllLines(path).asSequence()
        .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .lastOrNull { it[0].trim() == name }?.get(1)?.trim()

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
