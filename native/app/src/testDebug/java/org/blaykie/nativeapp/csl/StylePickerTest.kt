package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class StylePickerTest {
    private fun style(id: String, format: String?, kind: String? = "in-text", dependent: Boolean = false) =
        StyleMetadata("$id.csl", "$id edition", id, kind, format, dependent, StyleSource.PRIVATE)

    @Test fun classifiesStylesAndRetainsTitles() {
        val choices = styleChoices(listOf(style("apa", "author-date"), style("numbers", "numeric"), style("note", "note", "note"), style("child", null, null, true), style("unknown", null)))
        assertEquals(StyleChoice(null, "APA 7 (built-in)"), choices[0])
        assertEquals("imported:apa.csl", choices[1].id)
        assertEquals("apa edition", choices[1].title)
        assertEquals(StyleSource.PRIVATE, choices[1].source)
        assertEquals("Numeric styles are not supported.", choices[2].disabledReason)
        assertEquals("Note-based styles are not supported.", choices[3].disabledReason)
        assertTrue(choices[4].disabledReason!!.contains("import the parent file first"))
        assertTrue(choices[4].dependent)
        assertEquals("Uses the parent style’s formatting.", DEPENDENT_STYLE_NOTE)
        assertNotNull(choices[5].disabledReason)
    }
    @Test fun ambiguousIdsAreDisabled() {
        assertTrue(styleChoices(listOf(style("same", "author-date"), style("same", "author-date"))).drop(1).all { it.disabledReason != null })
    }
}
