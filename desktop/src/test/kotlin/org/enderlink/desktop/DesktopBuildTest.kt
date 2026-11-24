package org.enderlink.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopBuildTest {
    @Test
    fun `packaged build information identifies the running code`() {
        assertTrue(DesktopBuild.version.isNotBlank())
        assertTrue(DesktopBuild.id.matches(Regex("[0-9a-f]{12}")))
        assertTrue(DesktopBuild.label.contains(DesktopBuild.id))
    }
}
