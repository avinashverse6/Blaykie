package org.blaykie.nativeapp.account

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class KeyScreenStateTest {
    @Test fun connectRequiresNonBlankKeyAndIdleState() {
        assertFalse(canConnectKey("", false, false))
        assertFalse(canConnectKey("  \n", false, false))
        assertTrue(canConnectKey("some-key", false, false))
        assertFalse(canConnectKey("some-key", true, false))
        assertFalse(canConnectKey("some-key", false, true))
    }

    @Test fun visibilityToggleAlternates() {
        assertTrue(nextKeyVisibility(false))
        assertFalse(nextKeyVisibility(true))
    }

    @Test fun keyFieldUsesNonSaveableMemoryAndClearsOnDisposal() {
        val root = listOf(File("../.."), File("."), File("../../.."))
            .first { File(it, "native/app/build.gradle.kts").isFile }
        val source = File(root, "native/app/src/main/java/org/blaykie/nativeapp/account/AccountScreens.kt").readText()
        val screen = source.substringAfter("private fun KeyScreen(").substringBefore("internal fun AccountSection")
        assertTrue(screen.contains("var key by remember { mutableStateOf(\"\") }"))
        assertFalse(screen.contains("rememberSaveable"))
        assertTrue(screen.contains("onDispose { key = \"\" }"))
        assertTrue(screen.contains("visible = nextKeyVisibility(visible)"))
    }
}
