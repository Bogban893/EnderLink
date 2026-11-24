package org.enderlink.desktop.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
