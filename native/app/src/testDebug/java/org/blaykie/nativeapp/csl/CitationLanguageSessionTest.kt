package org.blaykie.nativeapp.csl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test

class CitationLanguageSessionTest {
    @Test fun oldAndNewSessionFormats() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<Session>("""{"uri":"content://doc","fileName":"file.docx","sourceName":"abc.docx"}""")
        assertEquals("en-US", old.debugCitationLanguage)
        val chosen = old.copy(debugCitationLanguage = "xx-XX")
        assertEquals(chosen, json.decodeFromString<Session>(json.encodeToString(chosen)))
    }
    @Test fun removalFallsBackOnlyForSelectedLanguage() {
        assertEquals("en-US", languageAfterRemoval("xx-XX", "XX-xx"))
        assertEquals("fr-FR", languageAfterRemoval("fr-FR", "xx-XX"))
    }
}
