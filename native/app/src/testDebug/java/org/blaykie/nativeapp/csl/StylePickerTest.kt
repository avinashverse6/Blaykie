package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class StylePickerTest {
    private fun style(id: String, format: String?, kind: String? = "in-text", dependent: Boolean = false) =
        StyleMetadata("$id.csl", "$id edition", id, kind, format, dependent, StyleSource.PRIVATE)

    @Test fun classifiesStylesAndRetainsTitles() {
        val choices = styleChoices(listOf(style("apa", "author-date"), style("numbers", "numeric"), style("note", "note", "note"), style("child", null, null, true), style("unknown", null)))
        assertEquals(StyleChoice(null, "APA 7 (built-in)"), choices[0])
        assertEquals(StyleChoice("apa", "apa edition"), choices[1])
        assertEquals("Numeric styles are not supported.", choices[2].disabledReason)
        assertEquals("Note-based styles are not supported.", choices[3].disabledReason)
        assertEquals("Dependent styles are not supported.", choices[4].disabledReason)
        assertNotNull(choices[5].disabledReason)
    }
    @Test fun ambiguousIdsAreDisabled() {
        assertTrue(styleChoices(listOf(style("same", "author-date"), style("same", "author-date"))).drop(1).all { it.disabledReason != null })
    }
}
