package org.blaykie.nativeapp

import org.junit.Assert.*
import org.junit.Test

class RootNavigationTest {
    @Test fun tabNamesRestoreSafely() {
        RootTab.entries.forEach { assertEquals(it, rootTabFromName(it.name)) }
        assertEquals(RootTab.DOCUMENTS, rootTabFromName("obsolete"))
    }
    @Test fun reselectSignalsRootReset() {
        RootTab.entries.forEach { assertEquals(it to true, rootTabSelection(it, it)) }
        assertEquals(RootTab.LIBRARY to false, rootTabSelection(RootTab.DOCUMENTS, RootTab.LIBRARY))
    }
    @Test fun barOnlyOnRootsWithoutKeyboardOrModal() {
        listOf("HOME", "SETTINGS").forEach {
            assertTrue(showRootNavigation(it, false, false))
            assertFalse(showRootNavigation(it, true, false))
            assertFalse(showRootNavigation(it, false, true))
        }
        listOf("DOCUMENT", "MOBILE_VIEW", "EDITOR", "EXPORT", "BIBLIOGRAPHY", "ABOUT", "LAST_CRASH", "KEY", "FIRST_RUN").forEach {
            assertFalse(showRootNavigation(it, false, false))
        }
    }
}
