package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.Surface
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal class FriendsScreen(
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val ownId = field()
    val friendName = field()
    val friendId = field()
    val friendsList = label(messages.text("friends.loading"), 14, C.muted)
    val actionButtons = mutableListOf<JButton>()

    val panel: JPanel = page(messages.text("friends.title"), messages.text("friends.description")).apply {
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("friends.ownIdLabel"), 11, C.green, true)); add(gap(10)); ownId.isEditable = false
            add(horizontal(ownId, BlockButton(messages.text("action.copy")).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ownId.text), null)
                    onStatus(messages.text("friends.idCopied"))
                }
            }))
            add(gap(9)); add(label(messages.text("friends.exchangeHint"), 12, C.muted))
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("friends.addSection"), 11, C.green, true)); add(gap(13))
            add(label(messages.text("friends.nameLabel"), 13, C.text, true)); add(gap(6)); add(friendName); add(gap(12))
            add(label(messages.text("friends.idLabel"), 13, C.text, true)); add(gap(6)); add(friendId); add(gap(17))
            add(BlockButton(messages.text("action.addFriend"), true).also { actionButtons += it }.apply { addActionListener { onAdd() } })
        }); add(gap(20))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("friends.savedSection"), 11, C.green, true)); add(gap(11)); add(friendsList)
            add(gap(15)); add(BlockButton(messages.text("action.refreshFriends")).apply { addActionListener { onRefresh() } })
        })
    }
}
