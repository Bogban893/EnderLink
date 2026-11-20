package org.enderlink.agent

import org.enderlink.world.WorldRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val HELP = """EnderLink agent — локальный Vanilla Java сервер
  host --repository <каталог> --server-dir <каталог> --jar <server.jar> [--version <ID>] [--java <путь>]
  tunnel-host --peer <имя> [--profile <каталог>] [--target-host <адрес>] [--target-port <порт>]
  tunnel-join --peer <имя> [--profile <каталог>] [--local-port <порт>] [--relay <https://адрес>]

В каталоге сервера заранее должны находиться eula.txt с eula=true и, при необходимости,
server.properties. Мир копируется из проверенной версии репозитория в server-dir/world.
Команды в консоли: status, save, stop. После штатной остановки мир публикуется как новая версия.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) { println(HELP); return }
    try {
        val command = args[0]
        val allowed = when (command) {
            "host" -> setOf("--repository", "--server-dir", "--jar", "--version", "--java")
            "tunnel-host" -> setOf("--peer", "--profile", "--target-host", "--target-port")
            "tunnel-join" -> setOf("--peer", "--profile", "--local-port", "--relay")
            else -> throw IllegalArgumentException("Неизвестная команда")
        }
        val values = mutableMapOf<String, String>()
        require(args.size % 2 == 1) { "Параметры задаются парами" }
        for (index in 1 until args.size step 2) {
            val key = args[index]
            require(key in allowed && key !in values && args[index + 1].isNotBlank()) { "Некорректный параметр: $key" }
            values[key] = args[index + 1]
        }
        if (command == "tunnel-host") { GameTunnel.host(values); return }
        if (command == "tunnel-join") { GameTunnel.join(values); return }
        val repository = WorldRepository(Path.of(requireNotNull(values["--repository"]) { "Укажите --repository" }))
        val serverDir = Path.of(requireNotNull(values["--server-dir"]) { "Укажите --server-dir" })
        val jar = Path.of(requireNotNull(values["--jar"]) { "Укажите --jar" }).toAbsolutePath().normalize()
        if (!Files.isRegularFile(jar, NOFOLLOW_LINKS)) throw IOException("Не найден server.jar: $jar")
        val java = values["--java"] ?: "java"
        val server = VanillaServer(repository, serverDir, values["--version"])
        server.prepare()
        println("Подготовлена версия ${server.versionId}: ${server.worldDirectory}")
        server.start(listOf(java, "-jar", jar.toString(), "nogui"))
        println("Процесс сервера запущен. Команды: status, save, stop")
        val commands = LinkedBlockingQueue<String>()
        Thread({
            while (true) {
                val line = readlnOrNull() ?: break
                commands.put(line)
            }
            commands.put("stop")
        }, "enderlink-console").apply { isDaemon = true; start() }
        while (server.isRunning()) {
            val command = commands.poll(500, TimeUnit.MILLISECONDS) ?: continue
            when (command.trim()) {
                "status" -> println(server.status())
                "save" -> { server.save(); println("Команда сохранения отправлена") }
                "stop" -> {
                    val version = server.stopAndPublish()
                    println("Сервер остановлен. Версия опубликована: $version")
                    return
                }
                "" -> Unit
                else -> println("Команды: status, save, stop")
            }
        }
        throw IOException("Сервер завершился сам: ${server.status()}. Рабочий мир оставлен в ${server.worldDirectory}")
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка аргументов: ${e.message}\n$HELP")
        exitProcess(2)
    } catch (e: IOException) {
        System.err.println("Ошибка: ${e.message}")
        exitProcess(1)
    }
}
