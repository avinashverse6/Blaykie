package org.blaykie.nativeapp.citation

import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.docx.LocatorLabel
import org.junit.Assert.*
import org.junit.Test

class CitationSheetTest {
    @Test fun queryIsTrimmedOnlyForRequest() {
        val typed = "  Thorat  "
        val state = SearchState(query = typed)
        assertEquals("Thorat", searchQueryForRequest(state.query))
        assertEquals(typed, state.query)
        assertEquals("Thorat", searchQueryForRequest("Thorat "))
        assertEquals("", searchQueryForRequest("   "))
    }
    @Test fun locatorChipsUseTheRightEnumValues() {
        assertEquals(listOf(LocatorLabel.Page, LocatorLabel.Chapter, LocatorLabel.Paragraph, LocatorLabel.Section), citationLocatorLabels)
        assertEquals(listOf("Page", "Chapter", "Paragraph", "Section"), citationLocatorLabels.map { it.title })
        assertEquals(listOf("p.", "chap.", "para.", "sec."), citationLocatorLabels.map { it.abbreviation })
    }
    @Test fun footerLabelAndEnabledRules() {
        assertEquals("Insert citation", citationSubmitLabel(false))
        assertEquals("Save", citationSubmitLabel(true))
        assertFalse(citationSubmitEnabled(0, false, false, false))
        assertTrue(citationSubmitEnabled(1, false, false, false))
        assertFalse(citationSubmitEnabled(2, true, false, false))
        assertFalse(citationSubmitEnabled(1, false, true, false))
        assertFalse(citationSubmitEnabled(1, false, false, true))
    }
    @Test fun chipsOnlyForMultipleReferences() {
        assertFalse(showSelectedReferences(0))
        assertFalse(showSelectedReferences(1))
        assertTrue(showSelectedReferences(2))
        assertTrue(showSelectedReferences(3))
    }
    @Test fun indicTitlesRemainWhole() {
        assertEquals(3, citationTitleMaxLines("A long English title"))
        assertEquals(Int.MAX_VALUE, citationTitleMaxLines("हिन्दी में एक शीर्षक"))
        assertEquals(Int.MAX_VALUE, citationTitleMaxLines("ગુજરાતી શીર્ષક"))
    }
    @Test fun previewMatchesInsertionTrimming() {
        val item = CitationItem("x", "Title", emptyList(), "2020")
        val state = SearchState(editNumber = 1, existingItems = listOf(item), prefix = "  ", page = "  ")
        assertEquals(state.copy(prefix = "", page = "").preview(), state.preview())
    }
}
