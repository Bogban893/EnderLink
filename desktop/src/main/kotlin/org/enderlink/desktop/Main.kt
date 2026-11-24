package org.enderlink.desktop

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.DesktopFrame
import org.enderlink.desktop.ui.components.C
import java.awt.GraphicsEnvironment
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println(messages.text("app.headless"))
        return
    }
    SwingUtilities.invokeLater {
        JComponent.setDefaultLocale(messages.locale)
        UIManager.getDefaults().defaultLocale = messages.locale
        UIManager.put("OptionPane.yesButtonText", messages.text("dialog.yes"))
        UIManager.put("OptionPane.noButtonText", messages.text("dialog.no"))
        UIManager.put("OptionPane.okButtonText", messages.text("dialog.ok"))
        UIManager.put("OptionPane.cancelButtonText", messages.text("dialog.cancel"))
        UIManager.put("OptionPane.background", C.panel)
        UIManager.put("Panel.background", C.panel)
        UIManager.put("OptionPane.messageForeground", C.text)
        DesktopFrame().isVisible = true
    }
}
