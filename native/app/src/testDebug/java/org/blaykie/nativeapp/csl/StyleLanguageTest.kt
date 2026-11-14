package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.Json
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test

class StyleLanguageTest {
    private val imported = listOf(LocaleMetadata("xx-XX", "test.xml"))
    private fun choice(dependent: Boolean, locale: String?) = StyleChoice("style", "Title", dependent = dependent, defaultLocale = locale)
    @Test fun dependentDefaultsAndOverrides() {
        val dependent = choice(true, "xx-XX")
        assertEquals(LanguageResolution("xx-XX"), resolveStyleLanguage(dependent, "en-US", false, imported))
        assertEquals(LanguageResolution("en-US", "Language file xx-XX not imported; using English (US)"), resolveStyleLanguage(dependent, "en-US", false, emptyList()))
        assertEquals(LanguageResolution("en-US"), resolveStyleLanguage(dependent, "en-US", true, imported))
        assertEquals(LanguageResolution("fr-FR"), resolveStyleLanguage(dependent, "fr-FR", true, imported))
    }
    @Test fun independentDefaultsAndSummary() {
        assertEquals("xx-XX", resolveStyleLanguage(choice(false, "xx-XX"), "en-US", false, imported).tag)
        assertEquals("en-US", resolveStyleLanguage(choice(false, null), "xx-XX", false, imported).tag)
        assertEquals("en-US", resolveStyleLanguage(choice(false, "en-US"), "xx-XX", false, emptyList()).tag)
        assertEquals("Language: English (US)", styleLanguageSummary(null, "xx-XX"))
        assertEquals("Language: xx-XX", styleLanguageSummary("style", "xx-XX"))
    }
    @Test fun oldSessionLanguageKeepsPriorNonEnglishSelection() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<Session>("""{"uri":"u","fileName":"f","sourceName":"s"}""")
        val prior = json.decodeFromString<Session>("""{"uri":"u","fileName":"f","sourceName":"s","debugCitationLanguage":"xx-XX"}""")
        assertFalse(old.debugCitationLanguageExplicit)
        assertTrue(prior.debugCitationLanguageExplicit)
    }
}
