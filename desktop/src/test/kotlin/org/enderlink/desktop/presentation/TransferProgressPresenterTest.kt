package org.enderlink.desktop.presentation

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.screens.ReceiveScreen
import org.enderlink.desktop.ui.screens.SendScreen
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferProgressPresenterTest {
    @Test
    fun `progress updates the active screen on EDT and resets both screens between transfers`() {
        lateinit var send: SendScreen
        lateinit var receive: ReceiveScreen
        lateinit var progress: TransferProgressPresenter
        SwingUtilities.invokeAndWait {
            send = SendScreen({}, {}, {})
            receive = ReceiveScreen({}, {}, {}, {})
            progress = TransferProgressPresenter(send, receive)
            progress.reset()
        }
        progress.update(512, 1024)
        SwingUtilities.invokeAndWait {
            assertEquals(500, send.sendProgress.value)
            assertTrue(!send.sendProgress.isIndeterminate)
            assertTrue(send.sendProgressText.text.endsWith("50%"))
            assertEquals(0, receive.receiveProgress.value)
            assertEquals(messages.text("status.preparing"), receive.receiveProgressText.text)
            progress.receiving = true
            progress.reset()
            assertTrue(receive.receiveProgress.isIndeterminate)
        }
        progress.update(1024, 1024)
        SwingUtilities.invokeAndWait {
            assertEquals(0, send.sendProgress.value)
            assertEquals(messages.text("status.preparing"), send.sendProgressText.text)
            assertEquals(1000, receive.receiveProgress.value)
            assertTrue(receive.receiveProgressText.text.endsWith("100%"))
            progress.complete()
            assertEquals(1000, receive.receiveProgress.value)
            assertTrue(!receive.receiveProgress.isIndeterminate)
            progress.reset()
        }
        progress.update(0, 0)
        SwingUtilities.invokeAndWait {
            assertEquals(0, receive.receiveProgress.value)
            assertTrue(receive.receiveProgressText.text.endsWith("0%"))
            progress.fail()
            assertEquals(messages.text("status.errorShort"), receive.receiveProgressText.text)
            progress.update(1024, 1024)
        }
        SwingUtilities.invokeAndWait {
            assertEquals(messages.text("status.errorShort"), receive.receiveProgressText.text)
            progress.reset()
            progress.conflict()
            assertEquals(messages.text("conflict.saved"), receive.receiveProgressText.text)
            assertEquals(C.gold, receive.receiveProgress.foreground)
        }
    }
}
