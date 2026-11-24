package org.enderlink.desktop.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/** Immutable catalog. A formatter is created per call because MessageFormat is not thread-safe. */
internal class Messages(requestedLocale: Locale) {
    val locale: Locale = if (requestedLocale.language == "en") Locale.ENGLISH else Locale.forLanguageTag("ru")
    private val bundle = ResourceBundle.getBundle(
        "messages", locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    fun text(key: String, vararg arguments: Any?): String = MessageFormat(bundle.getString(key), locale).format(arguments)
}

/** Language is selected once at startup; changing it requires restarting the application. */
internal val messages = Messages(Locale.forLanguageTag(System.getProperty("enderlink.language", "ru").replace('_', '-')))
