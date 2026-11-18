package org.blaykie.nativeapp

import androidx.datastore.preferences.core.mutablePreferencesOf
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

    @Test fun buildDefaultAndSavedOverrideAreIndependent() {
        assertEquals(BuildConfig.DEBUG, BuildConfig.CUSTOM_STYLES_DEFAULT)
        assertEquals(true, parseSettings(null, null, null, true).customCitationStyles)
        assertEquals(false, parseSettings(null, null, null, false).customCitationStyles)
        assertEquals(false, parseSettings(null, null, false, true).customCitationStyles)
        assertEquals(true, parseSettings(null, null, true, false).customCitationStyles)
    }

    @Test fun selectionsRoundTripAndClearIndependently() {
        val preferences = mutablePreferencesOf()
        assertEquals(null to null, readSelections(preferences))
        preferences.putStyleId("style-id")
        preferences.putLanguageTag("fr-FR")
        val (styleId, languageTag) = readSelections(preferences)
        assertEquals("style-id" to "fr-FR", styleId to languageTag)
        val parsed = parseSettings(null, null, null, false, styleId, languageTag)
        assertEquals("style-id", parsed.styleId)
        assertEquals("fr-FR", parsed.languageTag)
        preferences.putStyleId(null)
        assertEquals(null to "fr-FR", readSelections(preferences))
        preferences.putLanguageTag(null)
        assertEquals(null to null, readSelections(preferences))
    }

    @Test fun textSizesScaleInOrder() {
        assertTrue(TextSize.Small.scale < TextSize.Normal.scale)
        assertTrue(TextSize.Normal.scale < TextSize.Large.scale)
    }
}
