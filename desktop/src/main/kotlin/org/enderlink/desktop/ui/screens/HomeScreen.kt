package org.enderlink.desktop.ui.screens

import org.enderlink.desktop.i18n.messages
import org.enderlink.desktop.ui.components.BlockButton
import org.enderlink.desktop.ui.components.C
import org.enderlink.desktop.ui.components.combo
import org.enderlink.desktop.ui.components.PixelScene
import org.enderlink.desktop.ui.components.Surface
import org.enderlink.desktop.ui.components.field
import org.enderlink.desktop.ui.components.gap
import org.enderlink.desktop.ui.components.horizontal
import org.enderlink.desktop.ui.components.label
import org.enderlink.desktop.ui.components.page
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class HomeScreen(
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenWorld: () -> Unit,
    onImportOwner: () -> Unit,
    onSendCurrent: () -> Unit = {},
    onSelectLibrary: (Int) -> Unit = {},
    onNewWorld: () -> Unit = {},
    onImportWorld: () -> Unit = {},
    onChooseLibraryRoot: () -> Unit = {},
) {
    val ownerField = field()
    val lanNameField = field()
    val statusWorld = label(messages.text("repository.unselected"), 19, C.text, true)
    val statusDetail = label(messages.text("repository.selectHint"), 13, C.muted)
    val playerHint = label("", 13, C.gold)
    val actionButtons = mutableListOf<JButton>()
    val sendCurrentButton = BlockButton(messages.text("action.sendCurrent"), true).apply {
        isVisible = false
        addActionListener { onSendCurrent() }
    }
    val libraryWorlds = combo(arrayOf<String>())
    val libraryRootField = field().apply { isEditable = false }

    val panel: JPanel = page(messages.text("home.title"), messages.text("home.description")).apply {
        add(PixelScene().apply { alignmentX = 0f })
        add(gap(16))
        add(horizontal(BlockButton(messages.text("action.send"), true).apply { addActionListener { onNavigate("send") } },
            BlockButton(messages.text("action.receive")).apply { addActionListener { onNavigate("receive") } }))
        add(gap(23))
        val summary = Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("home.activeVersion"), 11, C.green, true)); add(gap(10))
            add(statusWorld); add(gap(5)); add(statusDetail); add(gap(11)); add(playerHint)
            add(gap(17))
            add(horizontal(BlockButton(messages.text("action.refresh")).apply { addActionListener { onRefresh() } },
                BlockButton(messages.text("action.openWorld")).apply { addActionListener { onOpenWorld() } },
                sendCurrentButton.also { actionButtons += it }))
        }
        add(summary); add(gap(24))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("home.library"), 11, C.green, true)); add(gap(9))
            add(label(messages.text("player.ownerUuidLabel"), 13, C.text, true)); add(gap(7)); add(ownerField)
            add(gap(12)); add(label(messages.text("player.lanNameLabel"), 13, C.text, true)); add(gap(7)); add(lanNameField)
            add(gap(7)); add(label(messages.text("player.lanNameHint"), 12, C.muted)); add(gap(12))
            add(horizontal(libraryWorlds, BlockButton(messages.text("action.openLibraryWorld")).also { actionButtons += it }.apply {
                addActionListener { onSelectLibrary(libraryWorlds.selectedIndex) }
            }))
            add(gap(12))
            add(horizontal(BlockButton(messages.text("action.newWorld")).also { actionButtons += it }.apply {
                addActionListener { onNewWorld() }
            }, BlockButton(messages.text("action.importWorld")).also { actionButtons += it }.apply {
                addActionListener { onImportWorld() }
            }))
            add(gap(13)); add(label(messages.text("home.libraryLocation"), 12, C.muted)); add(gap(6))
            add(horizontal(libraryRootField, BlockButton(messages.text("action.changeLibraryRoot")).also { actionButtons += it }.apply {
                addActionListener { onChooseLibraryRoot() }
            }))
        }); add(gap(24))
        add(Surface().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(label(messages.text("home.legacyOwner"), 11, C.gold, true)); add(gap(9))
            add(label(messages.text("home.legacyOwnerHint"), 12, C.muted))
            add(gap(12)); add(BlockButton(messages.text("action.importOwner")).also { actionButtons += it }.apply {
                addActionListener { onImportOwner() }
            })
        }); add(gap(24))
        add(label(messages.text("home.howItWorks"), 12, C.gold, true)); add(gap(12))
        add(horizontal(
            stepCard("01", messages.text("home.step.select"), messages.text("home.step.selectHint")),
            stepCard("02", messages.text("home.step.send"), messages.text("home.step.sendHint")),
            stepCard("03", messages.text("home.step.play"), messages.text("home.step.playHint")),
        ))
    }

    private fun stepCard(number: String, title: String, description: String): JPanel = Surface(C.panelLight).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(label(number, 13, C.gold, true)); add(gap(8)); add(label(title, 16, C.text, true)); add(gap(7))
        add(label("<html><body style='width:230px'>$description</body></html>", 12, C.muted))
    }
}
