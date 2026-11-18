package org.blaykie.nativeapp.citation.processor

import kotlinx.coroutines.runBlocking
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.junit.Assert.*
import org.junit.Test

class BibliographyProcessorTest {
    @Test fun builtInUsesSameReferencesAndSessionContext() = runBlocking {
        val author = listOf(CitationAuthor("Ada", "Smith"))
        val a = CitationItem("a", "Alpha", author, "2020", itemType="book", publisher="Press")
        val b = CitationItem("b", "Beta", author, "2020", itemType="journalArticle", publicationTitle="Journal", volume="3")
        val input = listOf(b,a)
        val clusters = listOf(CitationCluster("first", listOf("b")), CitationCluster("second", listOf("a"), mode="narrative"))
        val result = BuiltInApaProcessor.bibliography(input, clusters)
        assertEquals(listOf("a", "b"), result.entries.map { it.itemIds.single() })
        assertEquals(CitationFormatting.referenceList(input), result.entries.joinToString("\n\n") { it.runs.joinToString("") { run -> run.text } })
        assertEquals(true, result.layout.hangingIndent)
        assertTrue(result.entries[0].runs.any { it.italic && it.text.contains("Alpha") })
        assertEquals(listOf("(Smith, 2020b)", "Smith (2020a)"), result.citations.map { it.text })
    }
}
