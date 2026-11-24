package org.enderlink.desktop.ui

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.UiScale
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.font
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.scroll
import org.enderlink.desktop.ui.components.stack
import org.enderlink.desktop.ui.screens.FriendsScreen
import org.enderlink.desktop.ui.screens.HomeScreen
import org.enderlink.desktop.ui.screens.ReceiveScreen
import org.enderlink.desktop.ui.screens.SendScreen
import org.enderlink.desktop.ui.screens.ServerScreen
import org.enderlink.desktop.presentation.DesktopController

internal class DesktopFrame : JFrame("EnderLink — миры без границ") {
    private val cards = JPanel(CardLayout()).apply { isOpaque = false }
    private val nav = mutableMapOf<String, JButton>()
    val repositoryField = field()
    private val statusLine = label("Готово к работе", 13, C.muted)

    val homeScreen = HomeScreen(::showPage, { controller.refreshStatus() },
        { controller.openWorldFolder() }, { controller.importOwner() })
    val sendScreen = SendScreen({ controller.chooseDirectory(it) },
        { controller.startSend() }, { controller.cancelOperation() })
    val receiveScreen = ReceiveScreen({ controller.chooseDirectory(it) },
        { controller.startReceive() }, { controller.cancelOperation() }, { controller.installMinecraft() })
    val serverScreen = ServerScreen({ controller.chooseDirectory(it) }, { controller.chooseFile(it) },
        { controller.servers.startServer() }, { controller.servers.stopServer() })
    val friendsScreen = FriendsScreen({ controller.addFriend() }, { controller.refreshFriends() }, ::status)
    private val actionButtons = homeScreen.actionButtons + sendScreen.actionButtons +
        receiveScreen.actionButtons + serverScreen.actionButtons + friendsScreen.actionButtons
    private val cancelButtons = sendScreen.cancelButtons + receiveScreen.cancelButtons
    private val controller = DesktopController(this)
    private val serverTimer = Timer(1000) { controller.servers.watchServer() }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = controller.closeWindow()
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
        setCanCancel(false)
        serverScreen.stopButton.isEnabled = false
        serverTimer.start()
        controller.start()
    }

    override fun dispose() {
        serverTimer.stop()
        super.dispose()
    }

    fun status(text: String) { statusLine.text = text }

    fun setBusy(busy: Boolean, cancellable: Boolean) {
        actionButtons.forEach { it.isEnabled = !busy }
        setCanCancel(busy && cancellable)
    }

    fun setCanCancel(enabled: Boolean) {
        cancelButtons.forEach { it.isEnabled = enabled }
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
        head.add(horizontal(repositoryField, BlockButton("Обзор…").apply { addActionListener { controller.chooseDirectory(repositoryField) } }))
        head.add(gap(18))
        add(head, BorderLayout.NORTH)
        cards.add(scroll(homeScreen.panel), "home")
        cards.add(scroll(sendScreen.panel), "send")
        cards.add(scroll(receiveScreen.panel), "receive")
        cards.add(scroll(serverScreen.panel), "server")
        cards.add(scroll(friendsScreen.panel), "friends")
        add(cards, BorderLayout.CENTER)
        repositoryField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { controller.saveSettings(); controller.refreshStatus() }
        })
    }

    private fun footer(): JPanel = JPanel(BorderLayout()).apply {
        background = C.sidebar
        border = EmptyBorder(12, 24, 12, 24)
        add(statusLine, BorderLayout.CENTER)
        add(label("ENDERLINK  •  DESKTOP", 10, C.muted, true), BorderLayout.EAST)
    }

    private fun showPage(id: String) {
        (cards.layout as CardLayout).show(cards, id)
        for ((page, button) in nav) {
            button.background = if (page == id) C.panelLight else C.sidebar
            button.foreground = if (page == id) C.green else C.muted
        }
    }
}
