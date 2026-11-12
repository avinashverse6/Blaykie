package org.blaykie.nativeapp.csl

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrativeAuthorTest {
    @Test fun replacesTwoAuthorConjunctionUsingLocaleTerm() {
        assertEquals("Smith and Jones", narrativeAuthor("Smith & Jones", "and", 2))
        assertEquals("Smith et Jones", narrativeAuthor("Smith &amp; Jones", "et", 2))
    }
    @Test fun replacesOnlyLastConjunctionForThreeAuthors() {
        assertEquals("Smith, Jones, and Patel", narrativeAuthor("Smith, Jones, & Patel", "and", 3))
    }
    @Test fun leavesNamesWithoutAConjunctionAndSingleGroupUnchanged() {
        assertEquals("Smith et al.", narrativeAuthor("Smith et al.", "and", 3))
        assertEquals("Research & Development", narrativeAuthor("Research & Development", "and", 1))
        assertEquals("Smith and Jones", narrativeAuthor("Smith and Jones", "and", 2))
    }
}
