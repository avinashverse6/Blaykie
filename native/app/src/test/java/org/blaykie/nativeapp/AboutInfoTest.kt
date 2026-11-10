package org.blaykie.nativeapp

import java.io.File
import org.blaykie.nativeapp.session.Screen
import org.blaykie.nativeapp.session.backTarget
import org.junit.Assert.*
import org.junit.Test

class AboutInfoTest {
    private fun root(): File = listOf(File("../.."), File("."), File("../../.."))
        .first { File(it, "native/app/build.gradle.kts").isFile && File(it, "LICENSE").isFile }
    @Test fun aboutMatchesVersionLicenseAndRepositoryNotices() {
        val root = root()
        val gradle = File(root, "native/app/build.gradle.kts").readText()
        val version = Regex("(?m)^\\s*versionName\\s*=\\s*\"([^\"]+)\"").find(gradle)!!.groupValues[1]
        assertEquals("1.0.1", version)
        assertEquals(version, BuildConfig.VERSION_NAME)
        assertTrue(Regex("(?m)^\\s*versionCode\\s*=\\s*2\\s*$").containsMatchIn(gradle))
        assertEquals("Blaykie", AboutInfo.appName)
        assertEquals("Licensed under AGPL-3.0-or-later", AboutInfo.license)
        assertEquals("https://github.com/avinashverse6/Blaykie", AboutInfo.source)
        assertArrayEquals(File(root, "LICENSE").readBytes(), File(root, "native/app/src/main/assets/${AboutInfo.licenseAsset}").readBytes())
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        AboutInfo.libraries.forEach { library ->
            assertTrue("Missing ${library.name}", notices.contains(library.name))
            assertTrue("Missing license ${library.license}", notices.contains(library.license))
            assertTrue("Missing source ${library.url}", notices.contains(library.url))
        }
        assertEquals(Screen.SETTINGS, backTarget(Screen.ABOUT))
    }
}
