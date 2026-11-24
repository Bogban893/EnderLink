package org.enderlink.desktop

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.DesktopFrame
import org.enderlink.desktop.ui.components.C
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println(messages.text("app.headless"))
        return
    }
    SwingUtilities.invokeLater {
        UIManager.put("OptionPane.background", C.panel)
        UIManager.put("Panel.background", C.panel)
        UIManager.put("OptionPane.messageForeground", C.text)
        DesktopFrame().isVisible = true
    }
}
