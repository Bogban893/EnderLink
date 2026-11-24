package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
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
                view.serverScreen.serverStatus.text = messages.text("server.stoppedCheckLog", sessionDirectory)
            }
            view.serverScreen.stopButton.isEnabled = isRunning
        }
    }

    fun startServer() {
        if (server?.isRunning() == true) { dialogs.warning(messages.text("server.alreadyRunning")); return }
        val repository = repository() ?: return
        val base = view.serverScreen.serverBaseField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning(messages.text("server.directory.invalid")); return }
        }
        val jar = view.serverScreen.jarField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { dialogs.warning(messages.text("server.jar.invalid")); return }
        }
        if (base == null || !Files.isDirectory(base)) { dialogs.warning(messages.text("server.directory.required")); return }
        if (jar == null || !Files.isRegularFile(jar)) { dialogs.warning(messages.text("server.jar.required")); return }
        saveSettings()
        jobs.run(messages.text("server.starting"), {
            val session = createSession(base)
            val instance = VanillaServer(repository, session)
            instance.prepare()
            instance.start(listOf("java", "-jar", jar.toAbsolutePath().normalize().toString(), "nogui"))
            session to instance
        }) { (session, instance) ->
            sessionDirectory = session
            server = instance
            view.serverScreen.serverStatus.text = messages.text("server.running", instance.versionId.take(18))
            view.serverScreen.stopButton.isEnabled = true
            view.status(messages.text("server.started", session.resolve("enderlink-server.log")))
        }
    }

    private fun createSession(base: Path): Path {
        val eula = base.resolve("eula.txt")
        if (!Files.isRegularFile(eula) || !Files.readAllLines(eula).any { it.trim().equals("eula=true", true) }) {
            throw IOException(messages.text("server.eula.required"))
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
        jobs.run(messages.text("server.stopping"), { instance.stopAndPublish() }) { version ->
            server = null
            stoppingServer = false
            view.serverScreen.serverStatus.text = messages.text("server.saved")
            view.status(messages.text("server.versionPublished", shortId(version)))
            refreshStatus()
            if (closeAfterServerStop) view.dispose()
        }
    }

    fun watchServer() {
        val instance = server ?: return
        if (instance.isRunning() || stoppingServer || jobs.isBusy) return
        stoppingServer = true
        view.serverScreen.stopButton.isEnabled = false
        jobs.run(messages.text("server.exited"), { instance.publishStopped() }) { version ->
            server = null; stoppingServer = false
            view.serverScreen.serverStatus.text = messages.text("server.saved")
            view.status(messages.text("server.worldPublished", shortId(version)))
            refreshStatus()
        }
    }
}
