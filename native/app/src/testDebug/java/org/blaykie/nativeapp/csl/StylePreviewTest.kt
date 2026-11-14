package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.citation.processor.*
import org.junit.Assert.*
import org.junit.Test

class StylePreviewTest {
    @Test fun fixedPreviewHasOneTwoThreeAuthorsAndAllCitationForms() {
        val data = StylePreviewData
        assertEquals(listOf(1, 2, 3), data.items.map { it.authors.size })
        assertEquals(listOf("parenthetical", "narrative", "parenthetical", "narrative", "parenthetical", "narrative"), data.clusters.map { it.mode })
        val result = BibliographyResult(data.items.map { BibliographyEntry(listOf(it.key), listOf(RichRun("reference ${it.key}"))) }, BibliographyLayout(), data.clusters.map { ClusterResult(it.id, "text ${it.id}") })
        val preview = data.build("Example", result)
        assertEquals("Example", preview.title); assertEquals(listOf("1 author", "2 authors", "3 authors"), preview.rows.map { it.label })
        assertTrue(preview.rows[0].parenthetical.contains("PREVIEW1-p")); assertTrue(preview.rows[1].narrative.contains("PREVIEW2-n")); assertTrue(preview.rows[2].bibliography.contains("PREVIEW3"))
    }
}
