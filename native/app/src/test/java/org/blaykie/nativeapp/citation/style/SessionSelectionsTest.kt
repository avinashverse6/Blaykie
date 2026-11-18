package org.blaykie.nativeapp.citation.style

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.blaykie.nativeapp.OptionalSelectionState
import org.blaykie.nativeapp.putOptionalSelection
import org.blaykie.nativeapp.readOptionalSelection
import org.blaykie.nativeapp.csl.LocaleMetadata
import org.blaykie.nativeapp.csl.StyleChoice
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionsTest {
    private val old = Session("uri", "old.docx", "source", debugCitationStyleId = "old", debugCitationLanguage = "fr-FR", debugCitationLanguageExplicit = true)
    private val choices = listOf(StyleChoice(null, "APA"), StyleChoice("old", "Old"), StyleChoice("new", "New", defaultLocale = "de-DE"))
    private val locales = listOf(LocaleMetadata("fr-FR", "fr.xml"), LocaleMetadata("de-DE", "de.xml"))
    @Test fun adoptOnceAndSubsequentSettingsWin() {
        val (adopted, first) = reconcileSelections(OptionalSelectionState(null, null, false), old, true, choices, locales)
        assertEquals("old", adopted.styleId); assertEquals("fr-FR", adopted.languageTag)
        assertTrue(adopted.styleInitialized); assertEquals("old", first.debugCitationStyleId)
        val chosen = adopted.copy(styleId = "new", languageTag = null)
        val (after, next) = reconcileSelections(chosen, old, true, choices, locales)
        assertEquals(chosen, after); assertEquals("new", next.debugCitationStyleId)
        assertEquals("de-DE", next.debugCitationLanguage); assertFalse(next.debugCitationLanguageExplicit)
        val (_, builtIn) = reconcileSelections(chosen.copy(styleId = null), old, true, choices, locales)
        assertNull(builtIn.debugCitationStyleId)
    }
    @Test fun removedStyleAndLanguageFallBackIndependently() {
        val stored = OptionalSelectionState("new", "fr-FR", true)
        val (styleMissing, noStyle) = reconcileSelections(stored, old, false, listOf(choices[0]), locales)
        assertNotNull(styleMissing.styleMessage); assertNull(styleMissing.languageMessage)
        assertNull(noStyle.debugCitationStyleId); assertEquals("fr-FR", noStyle.debugCitationLanguage)
        val (localeMissing, noLocale) = reconcileSelections(stored, old, false, choices, listOf(locales[1]))
        assertNull(localeMissing.styleMessage); assertNotNull(localeMissing.languageMessage)
        assertEquals("new", noLocale.debugCitationStyleId)
        assertEquals("de-DE", noLocale.debugCitationLanguage)
        assertFalse(noLocale.debugCitationLanguageExplicit)
    }
    @Test fun adoptionMarkerAndMessagesRoundTripInFakePreferences() {
        val preferences = mutablePreferencesOf()
        val initial = readOptionalSelection(preferences)
        assertFalse(initial.styleInitialized)
        val (adopted, _) = reconcileSelections(initial, old, true, emptyList(), emptyList())
        preferences.putOptionalSelection(adopted)
        assertEquals(adopted, readOptionalSelection(preferences))
        val cleared = adopted.copy(styleId = null, languageTag = null, styleMessage = null, languageMessage = null)
        preferences.putOptionalSelection(cleared)
        val (after, session) = reconcileSelections(readOptionalSelection(preferences), old, true, choices, locales)
        assertEquals(cleared, after)
        assertNull(session.debugCitationStyleId)
        assertFalse(session.debugCitationLanguageExplicit)
    }
    @Test fun unavailableItemsFallBackWithoutLosingStoredSelections() {
        val stored = OptionalSelectionState("old", "fr-FR", true)
        val (after, session) = reconcileSelections(stored, old, false, listOf(choices[0]), emptyList())
        assertEquals("old", after.styleId); assertEquals("fr-FR", after.languageTag)
        assertNotNull(after.styleMessage); assertNotNull(after.languageMessage)
        assertNull(session.debugCitationStyleId); assertEquals("en-US", session.debugCitationLanguage)
        assertFalse(session.debugCitationLanguageExplicit)
        val (recovered, restored) = reconcileSelections(after, old, false, choices, locales)
        assertNull(recovered.styleMessage); assertNull(recovered.languageMessage)
        assertEquals("old", restored.debugCitationStyleId); assertEquals("fr-FR", restored.debugCitationLanguage)
    }
}
