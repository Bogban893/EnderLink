package org.enderlink.desktop.ui

import org.enderlink.agent.VanillaServer
import org.enderlink.network.iroh.IrohProfile
import org.enderlink.world.PlayerDataInspector
import org.enderlink.world.WorldConflictException
import org.enderlink.world.WorldRepository
import org.enderlink.worldtransfer.ParentFingerprint
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import org.enderlink.desktop.settings.DesktopPreferences
import org.enderlink.desktop.settings.DesktopSettings
import org.enderlink.desktop.ui.dialogs.DesktopDialogs
import org.enderlink.desktop.DesktopOperations
import org.enderlink.desktop.MinecraftSaves
import org.enderlink.desktop.Route
import org.enderlink.desktop.TransferTarget
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.UiScale
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.font
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import org.enderlink.desktop.ui.components.scroll
import org.enderlink.desktop.ui.components.stack
import org.enderlink.desktop.ui.screens.FriendsScreen
import org.enderlink.desktop.ui.screens.HomeScreen
import org.enderlink.desktop.ui.screens.ReceiveScreen
import org.enderlink.desktop.ui.screens.SendScreen
import org.enderlink.desktop.ui.screens.ServerScreen

internal class DesktopFrame : JFrame("EnderLink — миры без границ") {
    private val settings = DesktopSettings()
    private val dialogs = DesktopDialogs(this)
    private val operations = DesktopOperations()
    private val busy = AtomicBoolean(false)
    private val cards = JPanel(CardLayout()).apply { isOpaque = false }
    private val nav = mutableMapOf<String, JButton>()
    private var currentPage = "home"
    private var server: VanillaServer? = null
    private var sessionDirectory: Path? = null
    private var stoppingServer = false
    private var receivingTransfer = false
    @Volatile private var installingMinecraft = false
    private var lastParentFingerprint: ParentFingerprint? = null
    private var closeAfterServerStop = false
    private val lastProgressNs = AtomicLong(0)

    private val repositoryField = field()
    private val statusLine = label("Готово к работе", 13, C.muted)

    val homeScreen = HomeScreen(::showPage, ::refreshStatus, ::openWorldFolder, ::importOwner)
    val sendScreen = SendScreen(::chooseDirectory, ::startSend, ::cancelOperation)
    val receiveScreen = ReceiveScreen(::chooseDirectory, ::startReceive, ::cancelOperation, ::installMinecraft)
    val serverScreen = ServerScreen(::chooseDirectory, ::chooseFile, ::startServer, ::stopServer)
    val friendsScreen = FriendsScreen(::addFriend, ::refreshFriends, ::status)
    private val actionButtons = homeScreen.actionButtons + sendScreen.actionButtons +
        receiveScreen.actionButtons + serverScreen.actionButtons + friendsScreen.actionButtons
    private val cancelButtons = sendScreen.cancelButtons + receiveScreen.cancelButtons

    init {
        val preferences = settings.load()
        repositoryField.text = preferences.repository
        receiveScreen.savesField.text = preferences.saves
        receiveScreen.saveNameField.text = preferences.saveName
        serverScreen.serverBaseField.text = preferences.serverBase
        serverScreen.jarField.text = preferences.jar
        lastParentFingerprint = preferences.parentFingerprint
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                if (busy.get()) { error("Дождитесь завершения операции или отмените передачу"); return }
                if (server?.isRunning() == true) {
                    if (dialogs.confirmServerStop()) {
                        closeAfterServerStop = true
                        stopServer()
                    }
                    return
                }
                dispose()
            }
        })
        minimumSize = Dimension(980, 700)
        size = Dimension(1190, 790)
        setLocationRelativeTo(null)
        contentPane.background = C.background
        layout = BorderLayout()
        add(sidebar(), BorderLayout.WEST)
        add(content(), BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)
        UiScale.attach(this)
        showPage("home")
        cancelButtons.forEach { it.isEnabled = false }
        serverScreen.stopButton.isEnabled = false
        Timer(1000) { watchServer() }.start()
        refreshStatus()
        refreshFriends()
    }

    private fun sidebar(): JPanel = JPanel(BorderLayout()).apply {
        background = C.sidebar
        preferredSize = Dimension(222, 0)
        border = EmptyBorder(27, 16, 18, 16)
        val top = stack()
        top.add(label("✦  ENDERLINK", 20, C.green, true))
        top.add(gap(5))
        top.add(label("ТВОЙ МИР · ТВОИ ПРАВИЛА", 10, C.muted, true))
        top.add(gap(48))
        for ((id, title) in listOf(
            "home" to "▣   Обзор", "send" to "➜   Отправить мир", "receive" to "⇩   Принять мир",
            "server" to "▣   Сервер", "friends" to "♢   Друзья",
        )) {
            val button = JButton(title).apply {
                font = font(14, true); foreground = C.muted; background = C.sidebar
                horizontalAlignment = JLabel.LEFT; isFocusPainted = false
                border = EmptyBorder(12, 13, 12, 8)
                maximumSize = Dimension(Int.MAX_VALUE, 48)
                addActionListener { showPage(id) }
            }
            nav[id] = button
            top.add(button); top.add(gap(5))
        }
        add(top, BorderLayout.NORTH)
        val bottom = stack()
        bottom.add(label("JAVA EDITION", 11, C.gold, true))
        bottom.add(gap(7))
        bottom.add(label("МИР ПОД ТВОИМ КОНТРОЛЕМ", 10, C.muted))
        add(bottom, BorderLayout.SOUTH)
    }

    private fun content(): JPanel = JPanel(BorderLayout()).apply {
        background = C.background
        border = EmptyBorder(25, 29, 17, 29)
        val head = stack()
        head.add(label("ХРАНИЛИЩЕ МИРА", 11, C.green, true))
        head.add(gap(7))
        head.add(horizontal(repositoryField, BlockButton("Обзор…").apply { addActionListener { chooseDirectory(repositoryField) } }))
        head.add(gap(18))
        add(head, BorderLayout.NORTH)
        cards.add(scroll(homeScreen.panel), "home")
        cards.add(scroll(sendScreen.panel), "send")
        cards.add(scroll(receiveScreen.panel), "receive")
        cards.add(scroll(serverScreen.panel), "server")
        cards.add(scroll(friendsScreen.panel), "friends")
        add(cards, BorderLayout.CENTER)
        repositoryField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { saveSettings(); refreshStatus() }
        })
    }

    private fun footer(): JPanel = JPanel(BorderLayout()).apply {
        background = C.sidebar
        border = EmptyBorder(12, 24, 12, 24)
        add(statusLine, BorderLayout.CENTER)
        add(label("ENDERLINK  •  DESKTOP", 10, C.muted, true), BorderLayout.EAST)
    }

    private fun startSend() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер и опубликуйте его мир"); return }
        val source = sendScreen.sourceField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь мира"); return }
        }
        if (source != null && (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("level.dat")))) {
            error("Выберите папку закрытого мира с файлом level.dat"); return
        }
        val target = target(sendScreen.sendRoute, sendScreen.friendSend, sendScreen.sendHost, sendScreen.sendPort) ?: return
        val repository = repository() ?: return
        if (source != null) {
            val origin = source.toAbsolutePath().normalize()
            val repositoryRoot = repository.workingDirectory.parent
            if (origin != repository.workingDirectory &&
                (origin.startsWith(repositoryRoot) || repositoryRoot.startsWith(origin))) {
                error("Хранилище версий должно быть отдельно от исходного мира")
                return
            }
        }
        saveSettings()
        resetProgress()
        receivingTransfer = false
        job("Отправляем мир…", cancellable = true, {
            operations.send(repository, source, target, ::statusAsync, ::progressAsync)
        }) { result ->
            status("Мир передан и подтверждён · ${shortId(result.versionId)}")
            sendScreen.sendProgressText.text = "Готово · передано ${size(result.transferredBytes)}, повторно использовано ${size(result.reusedBytes)}"
            refreshStatus()
        }
    }

    private fun startReceive() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер"); return }
        val target = target(receiveScreen.receiveRoute, receiveScreen.friendReceive, null, receiveScreen.receivePort) ?: return
        val repository = repository() ?: return
        val saves = minecraftDestination() ?: return
        saveSettings()
        resetProgress()
        receivingTransfer = true
        job("Ожидаем мир…", cancellable = true, {
            if (repository.current() == null) {
                statusAsync("Сравниваем существующий мир в Minecraft…")
                val cached = MinecraftSaves.cacheExisting(repository, saves.first, saves.second)
                if (cached > 0) statusAsync("Повторно используем $cached блоков существующего мира")
            }
            if (operations.wasCancelled) throw InterruptedException("Операция отменена")
            val result = operations.receive(repository, target, ::statusAsync, ::progressAsync)
            SwingUtilities.invokeLater { lastParentFingerprint = result.parentFingerprint; saveSettings() }
            if (operations.wasCancelled) throw InterruptedException("Операция отменена")
            installingMinecraft = true
            SwingUtilities.invokeLater { cancelButtons.forEach { it.isEnabled = false } }
            statusAsync("Мир принят. Добавляем его в Minecraft…")
            val installed = try { MinecraftSaves.install(repository, saves.first, saves.second, result.parentFingerprint) }
            catch (error: Exception) {
                throw IOException("Мир получен в EnderLink, но не добавлен в Minecraft: ${error.message}. " +
                    "Закройте мир и нажмите «Обновить Minecraft».", error)
            }
            result to installed
        }) { (result, installed) ->
            status("Мир принят · ${shortId(result.versionId)}")
            receiveScreen.receiveProgressText.text = "Готово · получено ${size(result.transferredBytes)}, в Minecraft: ${installed.directory.fileName}"
            refreshStatus()
        }
    }

    private fun minecraftDestination(): Pair<Path, String>? {
        val path = receiveScreen.savesField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь папки saves"); return null }
        } ?: run { error("Выберите папку saves вашей установки Minecraft"); return null }
        val name = receiveScreen.saveNameField.text.trim()
        if (name.isBlank()) { error("Введите имя папки мира"); return null }
        return path to name
    }

    private fun installMinecraft() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер"); return }
        val repository = repository() ?: return
        val destination = minecraftDestination() ?: return
        saveSettings()
        job("Обновляем мир в Minecraft…", {
            MinecraftSaves.install(repository, destination.first, destination.second, lastParentFingerprint)
        }) { installed ->
            status("Мир Minecraft обновлён · ${installed.directory}")
            receiveScreen.receiveProgressText.text = "В Minecraft: ${installed.directory} · изменено ${installed.replacedFiles} файлов"
        }
    }

    private fun target(group: Pair<JRadioButton, JRadioButton>, friend: javax.swing.JComboBox<String>,
                       host: javax.swing.JTextField?, port: javax.swing.JTextField): TransferTarget? {
        if (group.first.isSelected) {
            val name = (friend.selectedItem as? String).orEmpty()
            if (name.isBlank()) { error("Добавьте друга и выберите его имя"); return null }
            return TransferTarget(Route.FRIEND, friend = name)
        }
        val number = port.text.trim().toIntOrNull()
        if (number == null || number !in 1..65535) { error("TCP-порт должен быть от 1 до 65535"); return null }
        val address = host?.text?.trim().orEmpty()
        if (host != null && address.isBlank()) { error("Введите адрес получателя"); return null }
        return TransferTarget(Route.LOCAL, host = address, port = number)
    }

    private fun startServer() {
        if (server?.isRunning() == true) { error("Сервер уже запущен"); return }
        val repository = repository() ?: return
        val base = serverScreen.serverBaseField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь настроек сервера"); return }
        }
        val jar = serverScreen.jarField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь server.jar"); return }
        }
        if (base == null || !Files.isDirectory(base)) { error("Выберите папку настроек сервера"); return }
        if (jar == null || !Files.isRegularFile(jar)) { error("Выберите файл server.jar"); return }
        saveSettings()
        job("Запускаем сервер…", {
            val session = createSession(base)
            val instance = VanillaServer(repository, session)
            instance.prepare()
            instance.start(listOf("java", "-jar", jar.toAbsolutePath().normalize().toString(), "nogui"))
            session to instance
        }) { (session, instance) ->
            sessionDirectory = session
            server = instance
            serverScreen.serverStatus.text = "Сервер работает · ${instance.versionId.take(18)}…"
            serverScreen.stopButton.isEnabled = true
            status("Сервер запущен · журнал: $session/enderlink-server.log")
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

    private fun stopServer() {
        val instance = server ?: return
        if (stoppingServer) return
        stoppingServer = true
        serverScreen.stopButton.isEnabled = false
        job("Сохраняем и останавливаем сервер…", { instance.stopAndPublish() }) { version ->
            server = null
            stoppingServer = false
            serverScreen.serverStatus.text = "Сервер остановлен · мир сохранён"
            status("Новая версия опубликована · ${shortId(version)}")
            refreshStatus()
            if (closeAfterServerStop) dispose()
        }
    }

    private fun watchServer() {
        val instance = server ?: return
        if (instance.isRunning() || stoppingServer || busy.get()) return
        stoppingServer = true
        serverScreen.stopButton.isEnabled = false
        job("Сервер завершился; проверяем мир…", { instance.publishStopped() }) { version ->
            server = null; stoppingServer = false
            serverScreen.serverStatus.text = "Сервер остановлен · мир сохранён"
            status("Мир сервера опубликован · ${shortId(version)}")
            refreshStatus()
        }
    }

    private fun addFriend() {
        val name = friendsScreen.friendName.text.trim(); val id = friendsScreen.friendId.text.trim()
        job("Сохраняем друга…", { IrohProfile.open().use { it.add(name, id) } }) {
            friendsScreen.friendName.text = ""; friendsScreen.friendId.text = ""
            status("Друг $name добавлен")
            refreshFriends()
        }
    }

    private fun importOwner() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер"); return }
        val owner = runCatching { UUID.fromString(homeScreen.ownerField.text.trim()) }.getOrNull()
        if (owner == null || owner.toString() != homeScreen.ownerField.text.trim().lowercase()) {
            error("Введите полный UUID вашего Minecraft аккаунта")
            return
        }
        val repository = repository() ?: return
        job("Переносим вещи владельца…", { repository.importLegacyPlayer(owner) }) { manifest ->
            status("Вещи владельца перенесены · ${shortId(manifest.versionId)}")
            refreshStatus()
        }
    }

    private fun refreshFriends() {
        job("Загружаем профиль…", { IrohProfile.open().use { it.id to it.friends() } }) { (id, friends) ->
            friendsScreen.ownId.text = id
            val previousSend = sendScreen.friendSend.selectedItem as? String
            val previousReceive = receiveScreen.friendReceive.selectedItem as? String
            sendScreen.friendSend.removeAllItems(); receiveScreen.friendReceive.removeAllItems()
            friends.keys.forEach { sendScreen.friendSend.addItem(it); receiveScreen.friendReceive.addItem(it) }
            if (previousSend in friends) sendScreen.friendSend.selectedItem = previousSend
            if (previousReceive in friends) receiveScreen.friendReceive.selectedItem = previousReceive
            friendsScreen.friendsList.text = if (friends.isEmpty()) "Пока никого нет — добавьте друга по ID." else
                "<html>" + friends.keys.joinToString("<br>") { "<span style='color:#84be60'>◆</span>  $it" } + "</html>"
            status("Профиль готов · друзей: ${friends.size}")
        }
    }

    private fun refreshStatus() {
        val path = repositoryField.text.trim()
        if (path.isBlank()) {
            homeScreen.statusWorld.text = "Мир ещё не выбран"; homeScreen.statusDetail.text = "Выберите хранилище версий выше"; homeScreen.playerHint.text = ""
            return
        }
        job("Читаем версию мира…", {
            val repo = WorldRepository(Path.of(path))
            val current = repo.current()
            val player = if (current == null) null else PlayerDataInspector.inspect(repo.workingDirectory)
            Triple(repo, current, player)
        }) { (repo, current, player) ->
            homeScreen.statusWorld.text = if (current == null) "Версий пока нет" else "Версия ${shortId(current.versionId)}"
            homeScreen.statusDetail.text = if (current == null) "Выберите закрытый мир на вкладке «Отправить»" else
                "Рабочая копия: ${repo.workingDirectory}"
            homeScreen.playerHint.text = when {
                player == null -> ""
                player.legacyEmbeddedPlayer -> "⚠ Данные владельца старого мира в level.dat — см. импорт ниже"
                player.singleplayerUuid && player.modernPlayerFiles == 0 -> "⚠ Проверьте файл игрока для singleplayer_uuid"
                else -> "Файлов игроков: ${player.legacyPlayerFiles + player.modernPlayerFiles}"
            }
            status("Состояние мира обновлено")
            if (friendsScreen.ownId.text.isBlank()) refreshFriends()
        }
    }

    private fun openWorldFolder() {
        val path = repositoryField.text.trim().takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() }?.resolve("world")
        if (path == null || !Files.isDirectory(path)) { error("Рабочая копия мира ещё не создана"); return }
        runCatching { java.awt.Desktop.getDesktop().open(path.toFile()) }
            .onFailure { error("Не удалось открыть папку: ${it.message}") }
    }

    private fun repository(): WorldRepository? {
        val path = repositoryField.text.trim()
        if (path.isBlank()) { error("Выберите хранилище мира вверху окна"); return null }
        return runCatching { WorldRepository(Path.of(path)) }.getOrElse { error(it.message ?: "Не удалось открыть хранилище"); null }
    }

    private fun <T> job(title: String, action: () -> T, success: (T) -> Unit) = job(title, false, action, success)

    private fun <T> job(title: String, cancellable: Boolean, action: () -> T, success: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { status("Дождитесь завершения текущей операции"); return }
        if (cancellable) operations.begin()
        actionButtons.forEach { it.isEnabled = false }
        cancelButtons.forEach { it.isEnabled = cancellable }
        status(title)
        Thread({
            val result = runCatching(action)
            SwingUtilities.invokeLater {
                busy.set(false)
                installingMinecraft = false
                actionButtons.forEach { it.isEnabled = true }
                cancelButtons.forEach { it.isEnabled = false }
                result.onSuccess(success).onFailure { failure ->
                    if (failure is InterruptedException || cancellable && operations.wasCancelled) status("Операция отменена")
                    else handleError(failure)
                    if (stoppingServer) {
                        stoppingServer = false
                        closeAfterServerStop = false
                        if (server?.isRunning() == false) {
                            server = null
                            serverScreen.serverStatus.text = "Сервер остановлен · проверьте журнал в $sessionDirectory"
                        }
                        serverScreen.stopButton.isEnabled = server?.isRunning() == true
                    }
                }
            }
        }, "enderlink-desktop-job").apply { isDaemon = true; start() }
    }

    private fun handleError(failure: Throwable) {
        if (failure is WorldConflictException) {
            status("Конфликт версий · входящая сохранена")
            if (dialogs.confirmIncomingVersion(failure.local, failure.incoming)) {
                val repository = repository() ?: return
                job("Активируем входящую версию…", { repository.checkout(failure.incoming) }) {
                    status("Входящая версия активирована. Нажмите «Обновить Minecraft» для установки.")
                    refreshStatus()
                }
            }
            return
        }
        val message = failure.message ?: failure.javaClass.simpleName
        status("Ошибка: $message")
        dialogs.error(message)
    }

    private fun cancelOperation() {
        if (!busy.get()) return
        if (installingMinecraft) { status("Завершаем обновление папки Minecraft…"); return }
        status("Отменяем операцию…")
        Thread({ operations.cancel() }, "enderlink-cancel").apply { isDaemon = true; start() }
    }

    private fun progressAsync(done: Long, total: Long) {
        val now = System.nanoTime()
        if (done < total && now - lastProgressNs.get() < 60_000_000L) return
        lastProgressNs.set(now)
        SwingUtilities.invokeLater {
            val value = if (total == 0L) 0 else ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
            val bar = if (receivingTransfer) receiveScreen.receiveProgress else sendScreen.sendProgress
            val text = if (receivingTransfer) receiveScreen.receiveProgressText else sendScreen.sendProgressText
            bar.value = value
            text.text = "${size(done)} из ${size(total)} · ${value / 10}%"
        }
    }

    private fun statusAsync(text: String) = SwingUtilities.invokeLater { status(text) }
    private fun status(text: String) { statusLine.text = text }
    private fun error(text: String) { dialogs.warning(text) }
    private fun resetProgress() {
        lastProgressNs.set(0)
        sendScreen.sendProgress.value = 0; receiveScreen.receiveProgress.value = 0
        sendScreen.sendProgressText.text = "Подготовка…"; receiveScreen.receiveProgressText.text = "Подготовка…"
    }

    private fun showPage(id: String) {
        currentPage = id
        (cards.layout as CardLayout).show(cards, id)
        for ((page, button) in nav) {
            button.background = if (page == id) C.panelLight else C.sidebar
            button.foreground = if (page == id) C.green else C.muted
        }
    }

    private fun saveSettings() {
        settings.save(DesktopPreferences(
            repository = repositoryField.text,
            saves = receiveScreen.savesField.text,
            saveName = receiveScreen.saveNameField.text,
            serverBase = serverScreen.serverBaseField.text,
            jar = serverScreen.jarField.text,
            parentFingerprint = lastParentFingerprint,
        ))
    }

    private fun chooseDirectory(target: javax.swing.JTextField) {
        dialogs.chooseDirectory(target.text)?.let {
            target.text = it
            saveSettings()
            if (target === repositoryField) refreshStatus()
        }
    }

    private fun chooseFile(target: javax.swing.JTextField) {
        dialogs.chooseJar()?.let { target.text = it; saveSettings() }
    }
}
