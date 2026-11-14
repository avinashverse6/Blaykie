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

    @Test fun customStylesDefaultOffForOlderSettingsAndReadSavedValue() {
        assertEquals(false, parseSettings(null, null).customCitationStyles)
        assertEquals(false, parseSettings("Light", "Large", false).customCitationStyles)
        assertEquals(true, parseSettings("Light", "Large", true).customCitationStyles)
        assertEquals(Settings(ThemeMode.Light, TextSize.Large, true), parseSettings("Light", "Large", true))
    }

    @Test fun textSizesScaleInOrder() {
        assertTrue(TextSize.Small.scale < TextSize.Normal.scale)
        assertTrue(TextSize.Normal.scale < TextSize.Large.scale)
    }
}
