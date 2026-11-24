package org.enderlink.desktop.ui

internal fun shortId(id: String) = id.removePrefix("sha256:").take(12)
internal fun size(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1f ГиБ".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f МиБ".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f КиБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
