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
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import org.enderlink.desktop.DesktopOperations
import org.enderlink.desktop.MinecraftSaves
import org.enderlink.desktop.Route
import org.enderlink.desktop.TransferTarget
import org.enderlink.desktop.ui.components.*

internal class DesktopFrame : JFrame("EnderLink — миры без границ") {
    private val settingsPath = Path.of(System.getProperty("user.home"), ".enderlink", "desktop.properties")
    private val settings = Properties()
    private val operations = DesktopOperations()
    private val busy = AtomicBoolean(false)
    private val cards = JPanel(CardLayout()).apply { isOpaque = false }
    private val nav = mutableMapOf<String, JButton>()
    private val actionButtons = mutableListOf<JButton>()
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
    private val sourceField = field()
    private val friendSend = combo(arrayOf(""))
    private val friendReceive = combo(arrayOf(""))
    private val sendHost = field("127.0.0.1")
    private val sendPort = field("25570")
    private val receivePort = field("25570")
    private val savesField = field()
    private val saveNameField = field()
    private val ownerField = field()
    private val serverBaseField = field()
    private val jarField = field()
    private val ownId = field()
    private val friendName = field()
    private val friendId = field()
    private val friendsList = label("Загрузка друзей…", 14, C.muted)
    private val sendRoute = routeGroup()
    private val receiveRoute = routeGroup()
    private val statusLine = label("Готово к работе", 13, C.muted)
    private val statusWorld = label("Мир ещё не выбран", 19, C.text, true)
    private val statusDetail = label("Выберите хранилище версий выше", 13, C.muted)
    private val playerHint = label("", 13, C.gold)
    private val serverStatus = label("Сервер не запущен", 15, C.muted)
    private val sendProgress = TransferBar()
    private val receiveProgress = TransferBar()
    private val sendProgressText = label("", 12, C.muted)
    private val receiveProgressText = label("", 12, C.muted)
    private val cancelButtons = mutableListOf<BlockButton>()
    private val stopButton = BlockButton("Сохранить и остановить", true)

    init {
        runCatching { Files.newInputStream(settingsPath).use(settings::load) }
        repositoryField.text = settings.getProperty("repository", "")
        savesField.text = settings.getProperty("saves", MinecraftSaves.defaultDirectory().toString())
        saveNameField.text = settings.getProperty("saveName", "EnderLink World")
        serverBaseField.text = settings.getProperty("serverBase", "")
        jarField.text = settings.getProperty("jar", "")
        lastParentFingerprint = runCatching {
            ParentFingerprint(
                settings.getProperty("parentVersion"),
                settings.getProperty("parentFormat").toInt(),
                settings.getProperty("parentHash"),
            )
        }.getOrNull()
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                if (busy.get()) { error("Дождитесь завершения операции или отмените передачу"); return }
                if (server?.isRunning() == true) {
                    val choice = JOptionPane.showConfirmDialog(this@DesktopFrame,
                        "Сохранить мир и остановить сервер перед выходом?", "Сервер работает", JOptionPane.YES_NO_OPTION)
                    if (choice == JOptionPane.YES_OPTION) {
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
        stopButton.isEnabled = false
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
        cards.add(scroll(homePage()), "home")
        cards.add(scroll(sendPage()), "send")
        cards.add(scroll(receivePage()), "receive")
        cards.add(scroll(serverPage()), "server")
        cards.add(scroll(friendsPage()), "friends")
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

    private fun homePage(): JPanel = page("ОБЗОР", "Всё нужное для передачи мира — в одном месте.").apply {
        add(PixelScene().apply { alignmentX = 0f })
        add(gap(16))
        add(horizontal(BlockButton("Отправить мир", true).apply { addActionListener { showPage("send") } },
            BlockButton("Принять мир").apply { addActionListener { showPage("receive") } }))
        add(gap(23))
        val summary = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("АКТИВНАЯ ВЕРСИЯ", 11, C.green, true)); add(gap(10))
            add(statusWorld); add(gap(5)); add(statusDetail); add(gap(11)); add(playerHint)
            add(gap(17))
            add(horizontal(BlockButton("Обновить").apply { addActionListener { refreshStatus() } },
                BlockButton("Открыть мир").apply { addActionListener { openWorldFolder() } }))
        }
        add(summary); add(gap(24))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ИНВЕНТАРЬ ВЛАДЕЛЬЦА СТАРОГО МИРА", 11, C.gold, true)); add(gap(9))
            add(label("Если старый одиночный мир хранит вещи в level.dat, укажите UUID Minecraft аккаунта.", 12, C.muted))
            add(gap(12)); add(horizontal(ownerField, BlockButton("Перенести вещи").also { actionButtons += it }.apply {
                addActionListener { importOwner() }
            }))
        }); add(gap(24))
        add(label("КАК ЭТО РАБОТАЕТ", 12, C.gold, true)); add(gap(12))
        add(horizontal(
            stepCard("01", "Выбери мир", "Закрой Minecraft и укажи папку сохранения."),
            stepCard("02", "Передай другу", "По ID друга или по адресу в локальной сети."),
            stepCard("03", "Продолжай играть", "Получатель запустит мир из своей копии."),
        ))
    }

    private fun stepCard(number: String, title: String, description: String): JPanel = Surface(C.panelLight).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(label(number, 13, C.gold, true)); add(gap(8)); add(label(title, 16, C.text, true)); add(gap(7))
        add(label("<html><body style='width:230px'>$description</body></html>", 12, C.muted))
    }

    private fun sendPage(): JPanel = page("ОТПРАВИТЬ МИР", "Сначала создадим проверенную версию, затем передадим только нужные блоки.").apply {
        val world = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("01  ИСТОЧНИК", 11, C.green, true)); add(gap(15))
            add(label("Папка закрытого мира", 13, C.text, true)); add(gap(7))
            add(horizontal(sourceField, BlockButton("Выбрать…").apply { addActionListener { chooseDirectory(sourceField) } }))
            add(gap(10)); add(label("Оставьте поле пустым, чтобы отправить уже опубликованную версию.", 12, C.muted))
            add(gap(12)); add(label("В Minecraft мир должен быть закрыт до начала передачи.", 12, C.gold))
        }
        add(world); add(gap(20))
        add(routePanel(sendRoute, friendSend, sendHost, sendPort, true)); add(gap(20))
        add(transferProgress(sendProgress, sendProgressText))
        add(gap(20))
        add(horizontal(BlockButton("Отправить мир  ➜", true).also { actionButtons += it }.apply {
            addActionListener { startSend() }
        }, BlockButton("Отменить").also { cancelButtons += it }.apply { addActionListener { cancelOperation() } }))
    }

    private fun receivePage(): JPanel = page("ПРИНЯТЬ МИР", "Открой ожидание, а друг выберет тебя на экране отправки.").apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("БЕЗОПАСНОЕ ПОЛУЧЕНИЕ", 11, C.green, true)); add(gap(11))
            add(label("Версия активируется после проверки всех блоков.", 15, C.text, true)); add(gap(8))
            add(label("При конфликте входящая версия сохранится, а выбор ветки останется за вами.", 12, C.muted))
        }
        add(info); add(gap(20))
        add(routePanel(receiveRoute, friendReceive, null, receivePort, false)); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("03  СОХРАНЕНИЕ В MINECRAFT", 11, C.green, true)); add(gap(12))
            add(label("Папка saves вашей установки Minecraft Java", 13, C.text, true)); add(gap(7))
            add(horizontal(savesField, BlockButton("Выбрать…").apply { addActionListener { chooseDirectory(savesField) } }))
            add(gap(12)); add(label("Имя папки мира", 13, C.text, true)); add(gap(7)); add(saveNameField)
            add(gap(10)); add(label("Мир появится в списке одиночной игры после получения. Закройте его перед обновлением.", 12, C.muted))
            add(gap(6)); add(label("Открывайте полученный мир совместимой версией Minecraft Java.", 12, C.gold))
        }); add(gap(20))
        add(transferProgress(receiveProgress, receiveProgressText))
        add(gap(20))
        add(horizontal(BlockButton("Ждать мир  ⇩", true).also { actionButtons += it }.apply {
            addActionListener { startReceive() }
        }, BlockButton("Отменить").also { cancelButtons += it }.apply {
            addActionListener { cancelOperation() }
        }, BlockButton("Обновить Minecraft").also { actionButtons += it }.apply {
            addActionListener { installMinecraft() }
        }))
    }

    private fun serverPage(): JPanel = page("СЕРВЕР", "Запусти опубликованный мир на своём компьютере.").apply {
        val info = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("СОСТОЯНИЕ", 11, C.green, true)); add(gap(11)); add(serverStatus)
            add(gap(9)); add(label("После остановки мир автоматически публикуется как новая версия.", 12, C.muted))
        }
        add(info); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("НАСТРОЙКИ VANILLA СЕРВЕРА", 11, C.green, true)); add(gap(14))
            add(label("Папка с eula.txt и server.properties", 13, C.text, true)); add(gap(7))
            add(horizontal(serverBaseField, BlockButton("Выбрать…").apply { addActionListener { chooseDirectory(serverBaseField) } }))
            add(gap(14)); add(label("Файл server.jar", 13, C.text, true)); add(gap(7))
            add(horizontal(jarField, BlockButton("Выбрать…").apply { addActionListener { chooseFile(jarField) } }))
            add(gap(12)); add(label("Для каждого запуска создаётся отдельная папка с копией мира и журналом.", 12, C.muted))
            add(gap(7)); add(label("В server.properties используйте online-mode=true и level-name=world.", 12, C.gold))
        }); add(gap(20))
        add(horizontal(BlockButton("Запустить сервер", true).also { actionButtons += it }.apply {
            addActionListener { startServer() }
        }, stopButton.apply { addActionListener { stopServer() } }))
    }

    private fun friendsPage(): JPanel = page("ДРУЗЬЯ", "Обменяйтесь ID один раз, затем выбирайте имя при передаче.").apply {
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ВАШ ID", 11, C.green, true)); add(gap(10)); ownId.isEditable = false
            add(horizontal(ownId, BlockButton("Копировать").apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ownId.text), null)
                    status("ID скопирован")
                }
            }))
            add(gap(9)); add(label("Отправьте ID другу. Он тоже должен добавить ваш ID у себя.", 12, C.muted))
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("ДОБАВИТЬ ДРУГА", 11, C.green, true)); add(gap(13))
            add(label("Имя", 13, C.text, true)); add(gap(6)); add(friendName); add(gap(12))
            add(label("ID друга", 13, C.text, true)); add(gap(6)); add(friendId); add(gap(17))
            add(BlockButton("Добавить друга", true).also { actionButtons += it }.apply { addActionListener { addFriend() } })
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label("СОХРАНЁННЫЕ ДРУЗЬЯ", 11, C.green, true)); add(gap(11)); add(friendsList)
            add(gap(15)); add(BlockButton("Обновить список").apply { addActionListener { refreshFriends() } })
        })
    }

    private fun routePanel(group: Pair<JRadioButton, JRadioButton>, friend: javax.swing.JComboBox<String>,
                           host: javax.swing.JTextField?, port: javax.swing.JTextField, sending: Boolean): JPanel = Surface().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(label("02  ПОДКЛЮЧЕНИЕ", 11, C.green, true)); add(gap(12))
        add(horizontal(group.first, group.second)); add(gap(16))
        add(label(if (sending) "Кому отправить" else "От кого принять", 13, C.text, true)); add(gap(7)); add(friend)
        add(gap(13))
        if (host != null) { add(label("Адрес в локальной сети", 13, C.text, true)); add(gap(7)); add(host); add(gap(13)) }
        add(label("TCP-порт", 13, C.text, true)); add(gap(7)); add(port)
        fun update() {
            friend.isEnabled = group.first.isSelected
            friend.background = if (group.first.isSelected) C.input else C.panelLight
            for (input in listOfNotNull(host, port)) {
                input.isEnabled = group.second.isSelected
                input.background = if (group.second.isSelected) C.input else C.panelLight
                input.foreground = if (group.second.isSelected) C.text else C.muted
            }
        }
        group.first.addActionListener { update() }
        group.second.addActionListener { update() }
        update()
    }

    private fun routeGroup(): Pair<JRadioButton, JRadioButton> {
        val first = JRadioButton("Через интернет · друг", true)
        val second = JRadioButton("Локальная сеть · TCP")
        ButtonGroup().apply { add(first); add(second) }
        for (button in listOf(first, second)) {
            button.font = font(13, true); button.foreground = C.text; button.background = C.panel
            button.isFocusPainted = false
            button.icon = PixelRadioIcon(false)
            button.selectedIcon = PixelRadioIcon(true)
            button.iconTextGap = 9
        }
        return first to second
    }

    private fun transferProgress(bar: TransferBar, text: JLabel): JPanel = Surface(C.panelLight).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(label("ХОД ПЕРЕДАЧИ", 11, C.green, true)); add(gap(12))
        add(bar); add(gap(8)); add(text)
    }

    private fun startSend() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер и опубликуйте его мир"); return }
        val source = sourceField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь мира"); return }
        }
        if (source != null && (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("level.dat")))) {
            error("Выберите папку закрытого мира с файлом level.dat"); return
        }
        val target = target(sendRoute, friendSend, sendHost, sendPort) ?: return
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
            sendProgressText.text = "Готово · передано ${size(result.transferredBytes)}, повторно использовано ${size(result.reusedBytes)}"
            refreshStatus()
        }
    }

    private fun startReceive() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер"); return }
        val target = target(receiveRoute, friendReceive, null, receivePort) ?: return
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
            receiveProgressText.text = "Готово · получено ${size(result.transferredBytes)}, в Minecraft: ${installed.directory.fileName}"
            refreshStatus()
        }
    }

    private fun minecraftDestination(): Pair<Path, String>? {
        val path = savesField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь папки saves"); return null }
        } ?: run { error("Выберите папку saves вашей установки Minecraft"); return null }
        val name = saveNameField.text.trim()
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
            receiveProgressText.text = "В Minecraft: ${installed.directory} · изменено ${installed.replacedFiles} файлов"
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
        val base = serverBaseField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { Path.of(raw) }.getOrElse { error("Некорректный путь настроек сервера"); return }
        }
        val jar = jarField.text.trim().takeIf(String::isNotEmpty)?.let { raw ->
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
            serverStatus.text = "Сервер работает · ${instance.versionId.take(18)}…"
            stopButton.isEnabled = true
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
        stopButton.isEnabled = false
        job("Сохраняем и останавливаем сервер…", { instance.stopAndPublish() }) { version ->
            server = null
            stoppingServer = false
            serverStatus.text = "Сервер остановлен · мир сохранён"
            status("Новая версия опубликована · ${shortId(version)}")
            refreshStatus()
            if (closeAfterServerStop) dispose()
        }
    }

    private fun watchServer() {
        val instance = server ?: return
        if (instance.isRunning() || stoppingServer || busy.get()) return
        stoppingServer = true
        stopButton.isEnabled = false
        job("Сервер завершился; проверяем мир…", { instance.publishStopped() }) { version ->
            server = null; stoppingServer = false
            serverStatus.text = "Сервер остановлен · мир сохранён"
            status("Мир сервера опубликован · ${shortId(version)}")
            refreshStatus()
        }
    }

    private fun addFriend() {
        val name = friendName.text.trim(); val id = friendId.text.trim()
        job("Сохраняем друга…", { IrohProfile.open().use { it.add(name, id) } }) {
            friendName.text = ""; friendId.text = ""
            status("Друг $name добавлен")
            refreshFriends()
        }
    }

    private fun importOwner() {
        if (server?.isRunning() == true) { error("Сначала остановите сервер"); return }
        val owner = runCatching { UUID.fromString(ownerField.text.trim()) }.getOrNull()
        if (owner == null || owner.toString() != ownerField.text.trim().lowercase()) {
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
            ownId.text = id
            val previousSend = friendSend.selectedItem as? String
            val previousReceive = friendReceive.selectedItem as? String
            friendSend.removeAllItems(); friendReceive.removeAllItems()
            friends.keys.forEach { friendSend.addItem(it); friendReceive.addItem(it) }
            if (previousSend in friends) friendSend.selectedItem = previousSend
            if (previousReceive in friends) friendReceive.selectedItem = previousReceive
            friendsList.text = if (friends.isEmpty()) "Пока никого нет — добавьте друга по ID." else
                "<html>" + friends.keys.joinToString("<br>") { "<span style='color:#84be60'>◆</span>  $it" } + "</html>"
            status("Профиль готов · друзей: ${friends.size}")
        }
    }

    private fun refreshStatus() {
        val path = repositoryField.text.trim()
        if (path.isBlank()) {
            statusWorld.text = "Мир ещё не выбран"; statusDetail.text = "Выберите хранилище версий выше"; playerHint.text = ""
            return
        }
        job("Читаем версию мира…", {
            val repo = WorldRepository(Path.of(path))
            val current = repo.current()
            val player = if (current == null) null else PlayerDataInspector.inspect(repo.workingDirectory)
            Triple(repo, current, player)
        }) { (repo, current, player) ->
            statusWorld.text = if (current == null) "Версий пока нет" else "Версия ${shortId(current.versionId)}"
            statusDetail.text = if (current == null) "Выберите закрытый мир на вкладке «Отправить»" else
                "Рабочая копия: ${repo.workingDirectory}"
            playerHint.text = when {
                player == null -> ""
                player.legacyEmbeddedPlayer -> "⚠ Данные владельца старого мира в level.dat — см. импорт ниже"
                player.singleplayerUuid && player.modernPlayerFiles == 0 -> "⚠ Проверьте файл игрока для singleplayer_uuid"
                else -> "Файлов игроков: ${player.legacyPlayerFiles + player.modernPlayerFiles}"
            }
            status("Состояние мира обновлено")
            if (ownId.text.isBlank()) refreshFriends()
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
                            serverStatus.text = "Сервер остановлен · проверьте журнал в $sessionDirectory"
                        }
                        stopButton.isEnabled = server?.isRunning() == true
                    }
                }
            }
        }, "enderlink-desktop-job").apply { isDaemon = true; start() }
    }

    private fun handleError(failure: Throwable) {
        if (failure is WorldConflictException) {
            status("Конфликт версий · входящая сохранена")
            val choice = JOptionPane.showConfirmDialog(this,
                "Входящая версия ${shortId(failure.incoming)} сохранена.\n" +
                    "Активная версия ${failure.local?.let(::shortId) ?: "отсутствует"}.\n\n" +
                    "Переключиться на входящую ветку?", "Конфликт версий", JOptionPane.YES_NO_OPTION)
            if (choice == JOptionPane.YES_OPTION) {
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
        JOptionPane.showMessageDialog(this, message, "EnderLink", JOptionPane.ERROR_MESSAGE)
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
            val bar = if (receivingTransfer) receiveProgress else sendProgress
            val text = if (receivingTransfer) receiveProgressText else sendProgressText
            bar.value = value
            text.text = "${size(done)} из ${size(total)} · ${value / 10}%"
        }
    }

    private fun statusAsync(text: String) = SwingUtilities.invokeLater { status(text) }
    private fun status(text: String) { statusLine.text = text }
    private fun error(text: String) { JOptionPane.showMessageDialog(this, text, "EnderLink", JOptionPane.WARNING_MESSAGE) }
    private fun resetProgress() {
        lastProgressNs.set(0)
        sendProgress.value = 0; receiveProgress.value = 0
        sendProgressText.text = "Подготовка…"; receiveProgressText.text = "Подготовка…"
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
        settings.setProperty("repository", repositoryField.text.trim())
        settings.setProperty("saves", savesField.text.trim())
        settings.setProperty("saveName", saveNameField.text.trim())
        lastParentFingerprint?.let {
            settings.setProperty("parentVersion", it.versionId)
            settings.setProperty("parentFormat", it.formatVersion.toString())
            settings.setProperty("parentHash", it.contentHash)
        } ?: run {
            settings.remove("parentVersion"); settings.remove("parentFormat"); settings.remove("parentHash")
        }
        settings.setProperty("serverBase", serverBaseField.text.trim())
        settings.setProperty("jar", jarField.text.trim())
        runCatching {
            Files.createDirectories(settingsPath.parent)
            Files.newOutputStream(settingsPath).use { settings.store(it, "EnderLink Desktop paths") }
        }
    }

    private fun chooseDirectory(target: javax.swing.JTextField) {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            target.text.takeIf(String::isNotBlank)?.let { raw ->
                runCatching { Path.of(raw).toFile() }.getOrNull()?.let { currentDirectory = it }
            }
        }.let { chooser ->
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.text = chooser.selectedFile.absolutePath
                saveSettings()
                if (target === repositoryField) refreshStatus()
            }
        }
    }

    private fun chooseFile(target: javax.swing.JTextField) {
        val chooser = JFileChooser()
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Java Archive (*.jar)", "jar")
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.text = chooser.selectedFile.absolutePath; saveSettings()
        }
    }
}

