package org.blaykie.nativeapp

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun stepsAreNumberedWithAndWithoutAnOptionalStep() {
        val plain = homeSteps(null)
        assertEquals(listOf("01", "02", "03"), plain.map { it.number })
        assertEquals(listOf("Open your document", "Insert Zotero citations", "Export a new doc with bibliography"), plain.map { it.label })
        val withExtra = homeSteps(HomeExtraStep("Test choice", 123))
        assertEquals(listOf("01", "02", "03", "04"), withExtra.map { it.number })
        assertEquals("Test choice", withExtra[1].label)
        assertEquals("Insert Zotero citations", withExtra[2].label)
    }
    @Test fun stepsStackForNarrowOrLargeText() {
        assertTrue(verticalHomeSteps(359f, TextSize.Normal, 1f))
        assertFalse(verticalHomeSteps(360f, TextSize.Normal, 1f))
        assertTrue(verticalHomeSteps(500f, TextSize.Large, 1f))
        assertTrue(verticalHomeSteps(500f, TextSize.Normal, 1.3f))
    }
}
