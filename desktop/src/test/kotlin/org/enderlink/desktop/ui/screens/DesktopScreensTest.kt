package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.i18n.messages
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopScreensTest {
    @Test
    fun `screens forward actions without starting operations during construction`() = onEdt {
        val calls = mutableListOf<String>()
        var chosenField: JTextField? = null
        val choose: (JTextField) -> Unit = { chosenField = it }
        val home = HomeScreen({ calls += it }, { calls += "refresh" }, { calls += "open" }, { calls += "owner" })
        val send = SendScreen(choose, { calls += "send" }, { calls += "cancel-send" })
        val receive = ReceiveScreen(choose, { calls += "receive" }, { calls += "cancel-receive" }, { calls += "install" })
        val server = ServerScreen(choose, choose, { calls += "start" }, { calls += "stop" })
        val friends = FriendsScreen({ calls += "add" }, { calls += "friends" }, { calls += it })
        assertTrue(calls.isEmpty())
        assertFalse(friends.ownId.isEditable)

        home.panel.click(messages.text("action.send"))
        home.panel.click(messages.text("action.receive"))
        home.panel.click(messages.text("action.refresh"))
        home.panel.click(messages.text("action.openWorld"))
        home.panel.click(messages.text("action.importOwner"))
        send.panel.click(messages.text("action.select"))
        assertSame(send.sourceField, chosenField)
        send.actionButtons.single().doClick(0)
        send.cancelButtons.single().doClick(0)
        receive.panel.click(messages.text("action.select"))
        assertSame(receive.savesField, chosenField)
        receive.panel.click(messages.text("action.waitWorld"))
        receive.cancelButtons.single().doClick(0)
        receive.panel.click(messages.text("action.install"))
        server.panel.click(messages.text("action.startServer"))
        server.stopButton.doClick(0)
        friends.panel.click(messages.text("action.addFriend"))
        friends.panel.click(messages.text("action.refreshFriends"))

        assertEquals(listOf("send", "receive", "refresh", "open", "owner", "send", "cancel-send",
            "receive", "cancel-receive", "install", "start", "stop", "add", "friends"), calls)
    }

    @Test
    fun `switching transfer route enables only inputs for that route and keeps entered values`() = onEdt {
        val send = SendScreen({}, {}, {})
        val receive = ReceiveScreen({}, {}, {}, {})
        send.sendHost.text = "192.168.1.10"
        send.sendPort.text = "25571"
        send.friendSend.addItem("Alex")
        send.friendSend.selectedItem = "Alex"
        assertTrue(send.friendSend.isEnabled)
        assertFalse(send.sendHost.isEnabled)
        assertFalse(send.sendPort.isEnabled)
        assertTrue(receive.friendReceive.isEnabled)
        assertFalse(receive.receivePort.isEnabled)

        send.sendRoute.second.doClick(0)
        receive.receiveRoute.second.doClick(0)
        assertFalse(send.friendSend.isEnabled)
        assertTrue(send.sendHost.isEnabled)
        assertTrue(send.sendPort.isEnabled)
        assertFalse(receive.friendReceive.isEnabled)
        assertTrue(receive.receivePort.isEnabled)

        send.sendRoute.first.doClick(0)
        receive.receiveRoute.first.doClick(0)
        assertTrue(send.friendSend.isEnabled)
        assertFalse(send.sendHost.isEnabled)
        assertFalse(send.sendPort.isEnabled)
        assertTrue(receive.friendReceive.isEnabled)
        assertFalse(receive.receivePort.isEnabled)
        assertEquals("Alex", send.friendSend.selectedItem)
        assertEquals("192.168.1.10", send.sendHost.text)
        assertEquals("25571", send.sendPort.text)
    }

    private fun Container.click(text: String) {
        descendants().filterIsInstance<JButton>().single { it.text == text }.doClick(0)
    }

    private fun Container.descendants(): Sequence<Component> = components.asSequence().flatMap {
        sequenceOf(it) + if (it is Container) it.descendants() else emptySequence()
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
