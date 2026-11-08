package org.blaykie.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun defaultsForMissingOrInvalidValues() {
        assertEquals(Settings(), parseSettings(null, null))
        assertEquals(Settings(), parseSettings("invalid", "invalid"))
    }

    @Test fun readsEverySavedOption() {
        for (theme in ThemeMode.entries) for (size in TextSize.entries) {
            assertEquals(Settings(theme, size), parseSettings(theme.name, size.name))
        }
    }

    @Test fun textSizesScaleInOrder() {
        assertTrue(TextSize.Small.scale < TextSize.Normal.scale)
        assertTrue(TextSize.Normal.scale < TextSize.Large.scale)
    }
}
