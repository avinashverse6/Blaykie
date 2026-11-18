package org.blaykie.nativeapp.citation.style

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.blaykie.nativeapp.OptionalSelectionState
import org.blaykie.nativeapp.putOptionalSelection
import org.blaykie.nativeapp.readOptionalSelection
import org.blaykie.nativeapp.selectStyle
import org.blaykie.nativeapp.selectLanguage
import org.blaykie.nativeapp.csl.StyleChoice
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test

class DocumentCitationUiTest {
    private val choices = listOf(StyleChoice(null, "APA 7 (built-in)"), StyleChoice("asa", "ASA, 6th edition"))
    @Test fun bannerOnlyForPreparingOrFailedCustomStyle() {
        assertEquals(CitationBanner.None, citationBanner(false, "asa", "asa", true, null))
        assertEquals(CitationBanner.None, citationBanner(true, null, null, true, "asa"))
        assertEquals(CitationBanner.None, citationBanner(true, "asa", "asa", false, null))
        assertEquals(CitationBanner.None, citationBanner(true, "asa", null, false, null))
        assertEquals(CitationBanner.Preparing, citationBanner(true, "asa", "asa", true, null))
        assertEquals(CitationBanner.Failed, citationBanner(true, "asa", null, false, "asa"))
        assertEquals(CitationBanner.None, citationBanner(true, "asa", null, false, "other"))
    }
    @Test fun subtitleOnlyForReadyCustomStyleAndNonEnglishLanguage() {
        assertNull(citationSubtitle(false, "asa", true, "fr-FR", choices))
        assertNull(citationSubtitle(true, null, true, "fr-FR", choices))
        assertNull(citationSubtitle(true, "asa", false, "fr-FR", choices))
        assertNull(citationSubtitle(true, "missing", true, "fr-FR", choices))
        assertEquals("ASA, 6th edition", citationSubtitle(true, "asa", true, "en-GB", choices))
        assertEquals("ASA, 6th edition · fr-FR", citationSubtitle(true, "asa", true, "fr-FR", choices))
    }
    @Test fun rowMessagesClearOnChoiceAndDismissalSurvivesReconciliation() {
        val prefs = mutablePreferencesOf()
        val missing = OptionalSelectionState("gone", "fr-FR", true, "style message", "language message")
        prefs.putOptionalSelection(missing)
        assertEquals("style message", readOptionalSelection(prefs).styleMessage)
        assertEquals("language message", readOptionalSelection(prefs).languageMessage)
        prefs.selectStyle("asa")
        assertNull(readOptionalSelection(prefs).styleMessage)
        assertEquals("language message", readOptionalSelection(prefs).languageMessage)
        prefs.selectLanguage(null)
        assertNull(readOptionalSelection(prefs).languageMessage)
        prefs.putOptionalSelection(missing)
        prefs.putOptionalSelection(dismissLanguageMessage(dismissStyleMessage(readOptionalSelection(prefs))))
        val (rechecked, _) = reconcileSelections(readOptionalSelection(prefs), Session("u", "f", "s"), false, choices, emptyList())
        assertNull(rechecked.styleMessage)
        assertNull(rechecked.languageMessage)
        assertEquals("gone", rechecked.styleId)
        assertEquals("fr-FR", rechecked.languageTag)
        prefs.putOptionalSelection(rechecked)
        prefs.selectStyle("asa")
        prefs.selectLanguage("en-US")
        assertNull(readOptionalSelection(prefs).styleMessageDismissedFor)
        assertNull(readOptionalSelection(prefs).languageMessageDismissedFor)
    }
}
