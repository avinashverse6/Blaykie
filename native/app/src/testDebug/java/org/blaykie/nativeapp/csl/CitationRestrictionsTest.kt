package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class CitationRestrictionsTest {
    @Test fun rejectsUnverifiedCombinationsOnlyInCsl() {
        val apa = "http://www.zotero.org/styles/apa"
        assertNull(citationRestriction(null, "narrative", 2, "", "see"))
        assertNotNull(citationRestriction(apa, "narrative", 2, "", ""))
        assertNotNull(citationRestriction(apa, "parenthetical", 2, "3", ""))
        assertNotNull(citationRestriction("other", "narrative", 1, "", "see"))
        assertNull(citationRestriction(apa, "narrative", 1, "3", "see"))
    }
}
