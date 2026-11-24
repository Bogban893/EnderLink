package org.enderlink.desktop.ui

import org.enderlink.desktop.i18n.messages

internal fun shortId(id: String) = id.removePrefix("sha256:").take(12)
internal fun size(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> messages.text("size.gib", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> messages.text("size.mib", bytes / (1024.0 * 1024))
    bytes >= 1024 -> messages.text("size.kib", bytes / 1024.0)
    else -> messages.text("size.bytes", bytes.toString())
}
