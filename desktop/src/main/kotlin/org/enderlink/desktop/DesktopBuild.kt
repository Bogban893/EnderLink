package org.enderlink.desktop

import java.util.Properties

/** Loaded once so the window identifies the code used by this process, including after a rebuild. */
internal object DesktopBuild {
    private val properties = Properties().apply {
        checkNotNull(DesktopBuild::class.java.getResourceAsStream("/enderlink-build.properties")) {
            "Missing build information. Build Desktop using the Gradle wrapper."
        }.use(::load)
    }
    val version: String = properties.getProperty("version")
    val id: String = properties.getProperty("id")
    val label: String = "$version [$id]"
}
