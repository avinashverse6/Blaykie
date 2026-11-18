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

class RemovalReconciliationTest {
    private val apa = StyleChoice(null, "APA")
    private val selected = StyleChoice("selected", "Selected", defaultLocale = "de-DE")
    private val other = StyleChoice("other", "Other")
    private val fr = LocaleMetadata("fr-FR", "fr.xml")
    private val de = LocaleMetadata("de-DE", "de.xml")
    private val before = SelectionInventory(listOf(apa, selected, other), listOf(fr, de))

    private class FakeCoordinator(initial: OptionalSelectionState, inventory: SelectionInventory) {
        private val preferences = mutablePreferencesOf().apply { putOptionalSelection(initial) }
        var inventory = inventory
        var signals = 0
        var session = reconcileSelections(initial, Session("uri", "file", "source"), false, inventory.choices, inventory.locales).second
        val settings get() = readOptionalSelection(preferences)
        fun remove(after: SelectionInventory) {
            val result = reconcileRemoval(settings, inventory, after)
            preferences.putOptionalSelection(result.settings)
            inventory = after
            if (result.changed) {
                signals++
                session = reconcileSelections(settings, session, false, after.choices, after.locales).second
            }
        }
    }

    @Test fun selectedStyleRemovalKeepsStoredIdAndSignalsBuiltInFallback() {
        val fake = FakeCoordinator(OptionalSelectionState("selected", "fr-FR", true), before)
        fake.remove(before.copy(choices = listOf(apa, other)))
        assertEquals("selected", fake.settings.styleId)
        assertEquals("Selected citation style is unavailable; using built-in APA.", fake.settings.styleMessage)
        assertNull(fake.session.debugCitationStyleId)
        assertEquals(1, fake.signals)
    }

    @Test fun selectedLocaleRemovalSignalsAutomaticLanguageAndMessage() {
        val fake = FakeCoordinator(OptionalSelectionState("selected", "fr-FR", true), before)
        fake.remove(before.copy(locales = listOf(de)))
        assertEquals("fr-FR", fake.settings.languageTag)
        assertEquals("Selected citation language is unavailable; using automatic language.", fake.settings.languageMessage)
        assertEquals("de-DE", fake.session.debugCitationLanguage)
        assertFalse(fake.session.debugCitationLanguageExplicit)
        assertEquals(1, fake.signals)
    }

    @Test fun removingUnselectedStyleOrLocaleDoesNotChangeSettingsSessionOrSignal() {
        val fake = FakeCoordinator(OptionalSelectionState("selected", "fr-FR", true), before)
        val settings = fake.settings
        val session = fake.session
        fake.remove(before.copy(choices = listOf(apa, selected)))
        fake.remove(fake.inventory.copy(locales = listOf(fr)))
        assertEquals(settings, fake.settings)
        assertEquals(session, fake.session)
        assertEquals(0, fake.signals)
    }

    @Test fun removingAutomaticStyleLocaleUpdatesSessionEvenWithoutMessageChange() {
        val fake = FakeCoordinator(OptionalSelectionState("selected", null, true), before)
        assertEquals("de-DE", fake.session.debugCitationLanguage)
        fake.remove(before.copy(locales = listOf(fr)))
        assertEquals("en-US", fake.session.debugCitationLanguage)
        assertNull(fake.settings.languageMessage)
        assertEquals(1, fake.signals)
    }
}
