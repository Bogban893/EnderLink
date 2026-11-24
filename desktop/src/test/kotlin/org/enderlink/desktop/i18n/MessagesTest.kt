package org.enderlink.desktop.i18n

import org.enderlink.network.TransferFailureKind
import java.text.MessageFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.Properties
import java.util.MissingResourceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun `Russian is the default catalog for unsupported languages`() {
        assertEquals("Введите адрес получателя", Messages(Locale.FRENCH).text("transfer.recipientAddress.required"))
        assertEquals("Введите адрес получателя", Messages(Locale.forLanguageTag("ru-RU")).text("transfer.recipientAddress.required"))
        assertEquals("Enter the recipient address", Messages(Locale.forLanguageTag("en-US")).text("transfer.recipientAddress.required"))
    }

    @Test
    fun `parameters preserve literal paths quotes and braces`() {
        val address = "friend's-{world}"
        assertEquals("Подключаемся к $address:25570…", Messages(Locale.forLanguageTag("ru")).text("transfer.connecting", address, "25570"))
        assertEquals("Connecting to $address:25570…", Messages(Locale.ENGLISH).text("transfer.connecting", address, "25570"))
    }

    @Test
    fun `both catalogs contain matching keys and usable templates with the same arguments`() {
        val russian = properties("messages.properties")
        val english = properties("messages_en.properties")
        assertEquals(russian.stringPropertyNames(), english.stringPropertyNames())
        for (key in russian.stringPropertyNames()) {
            val ru = russian.getProperty(key)
            val en = english.getProperty(key)
            assertTrue(ru.isNotBlank(), key)
            assertTrue(en.isNotBlank(), key)
            assertEquals(argumentIndexes(ru), argumentIndexes(en), key)
            for ((pattern, locale) in listOf(ru to Locale.forLanguageTag("ru"), en to Locale.ENGLISH)) {
                val format = MessageFormat(pattern, locale)
                val arguments = format.formatsByArgumentIndex.map { if (it is NumberFormat) 1.5 else "value" }.toTypedArray<Any>()
                val result = Messages(locale).text(key, *arguments)
                assertFalse(Regex("\\{\\d+").containsMatchIn(result), "$key: $result")
            }
        }
    }

    @Test
    fun `multiline prompts and sizes use the selected language`() {
        val ru = Messages(Locale.forLanguageTag("ru"))
        val en = Messages(Locale.ENGLISH)
        assertEquals("1,5 КиБ", ru.text("size.kib", 1.5))
        assertEquals("1.5 KiB", en.text("size.kib", 1.5))
        assertEquals("Incoming version incoming has been saved.\nActive version: local.\n\nSwitch to the incoming branch?",
            en.text("conflict.confirmIncoming", "incoming", "local"))
        assertEquals("Error: Minecraft's {world}", en.text("status.error", "Minecraft's {world}"))
        assertFailsWith<MissingResourceException> { en.text("missing.key") }
    }

    @Test
    fun `network failure messages give a next step in both languages`() {
        val ru = Messages(Locale.forLanguageTag("ru"))
        val en = Messages(Locale.ENGLISH)
        for (kind in TransferFailureKind.entries) {
            assertTrue(ru.text("network.error.${kind.name.lowercase()}").isNotBlank())
            assertTrue(en.text("network.error.${kind.name.lowercase()}").isNotBlank())
        }
        assertTrue(ru.text("network.error.connection_refused").contains("Ждать мир"))
        assertTrue(en.text("network.error.connection_refused").contains("Wait for world"))
        assertTrue(ru.text("network.error.no_response").contains("неизвестно"))
        assertTrue(en.text("network.error.no_response").contains("unknown"))
        assertTrue(ru.text("network.error.dialog", "Подсказка", "operation-id").contains("operation-id"))
    }

    private fun properties(name: String): Properties = Properties().apply {
        checkNotNull(MessagesTest::class.java.classLoader.getResourceAsStream(name)).reader(Charsets.UTF_8).use(::load)
    }

    private fun argumentIndexes(pattern: String): Set<String> = Regex("\\{(\\d+)[,}]")
        .findAll(pattern).map { it.groupValues[1] }.toSet()
}
