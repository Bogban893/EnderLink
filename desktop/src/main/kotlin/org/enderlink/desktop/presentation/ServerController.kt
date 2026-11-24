package org.enderlink.desktop.presentation

import org.enderlink.agent.VanillaServer
import org.enderlink.desktop.ui.DesktopFrame
import org.enderlink.desktop.ui.dialogs.DesktopDialogs
import org.enderlink.desktop.ui.shortId
import org.enderlink.world.WorldRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Owns the running server session and its save-before-close lifecycle. */
internal class ServerController(
    private val view: DesktopFrame,
    private val jobs: DesktopJobs,
    private val dialogs: DesktopDialogs,
    private val repository: () -> WorldRepository?,
    private val saveSettings: () -> Unit,
    private val refreshStatus: () -> Unit,
) {
    private var server: VanillaServer? = null
    private var sessionDirectory: Path? = null
    private var stoppingServer = false
    private var closeAfterServerStop = false
    val isRunning: Boolean get() = server?.isRunning() == true

    fun closeWindow() {
        if (isRunning) {
            if (dialogs.confirmServerStop()) {
                closeAfterServerStop = true
                stopServer()
            }
            return
        }
        view.dispose()
    }

    fun operationFailed() {
        if (stoppingServer) {
            stoppingServer = false
            closeAfterServerStop = false
            if (server?.isRunning() == false) {
                server = null
                view.serverScreen.serverStatus.text = "Сервер остановлен · проверьте журнал в $sessionDirectory"
            }
            view.serverScreen.stopButton.isEnabled = isRunning
        }
    }

    fun startServer() {
        if (server?.isRunning() == true) { dialogs.warning("Сервер уже запущен"); return }
        val repository = repository() ?: return
        val base = view.serverScreen.serverBaseField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning("Некорректный путь настроек сервера"); return }
        }
        val jar = view.serverScreen.jarField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning("Некорректный путь server.jar"); return }
        }
        if (base == null || !Files.isDirectory(base)) { dialogs.warning("Выберите папку настроек сервера"); return }
        if (jar == null || !Files.isRegularFile(jar)) { dialogs.warning("Выберите файл server.jar"); return }
        saveSettings()
        jobs.run("Запускаем сервер…", {
            val session = createSession(base)
            val instance = VanillaServer(repository, session)
            instance.prepare()
            instance.start(listOf("java", "-jar", jar.toAbsolutePath().normalize().toString(), "nogui"))
            session to instance
        }) { (session, instance) ->
            sessionDirectory = session
            server = instance
            view.serverScreen.serverStatus.text = "Сервер работает · ${instance.versionId.take(18)}…"
            view.serverScreen.stopButton.isEnabled = true
            view.status("Сервер запущен · журнал: $session/enderlink-server.log")
        }
    }

    private fun createSession(base: Path): Path {
        val eula = base.resolve("eula.txt")
        if (!Files.isRegularFile(eula) || !Files.readAllLines(eula).any { it.trim().equals("eula=true", true) }) {
            throw IOException("Примите Minecraft EULA в eula.txt (eula=true) в выбранной папке")
        }
        val sessions = base.resolve("enderlink-sessions")
        Files.createDirectories(sessions)
        val session = Files.createTempDirectory(sessions, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-")))
        Files.copy(eula, session.resolve("eula.txt"), StandardCopyOption.COPY_ATTRIBUTES)
        val properties = base.resolve("server.properties")
        if (Files.exists(properties)) Files.copy(properties, session.resolve("server.properties"), StandardCopyOption.COPY_ATTRIBUTES)
        return session
    }

    fun stopServer() {
        val instance = server ?: return
        if (stoppingServer) return
        stoppingServer = true
        view.serverScreen.stopButton.isEnabled = false
        jobs.run("Сохраняем и останавливаем сервер…", { instance.stopAndPublish() }) { version ->
            server = null
            stoppingServer = false
            view.serverScreen.serverStatus.text = "Сервер остановлен · мир сохранён"
            view.status("Новая версия опубликована · ${shortId(version)}")
            refreshStatus()
            if (closeAfterServerStop) view.dispose()
        }
    }

    fun watchServer() {
        val instance = server ?: return
        if (instance.isRunning() || stoppingServer || jobs.isBusy) return
        stoppingServer = true
        view.serverScreen.stopButton.isEnabled = false
        jobs.run("Сервер завершился; проверяем мир…", { instance.publishStopped() }) { version ->
            server = null; stoppingServer = false
            view.serverScreen.serverStatus.text = "Сервер остановлен · мир сохранён"
            view.status("Мир сервера опубликован · ${shortId(version)}")
            refreshStatus()
        }
    }
}
