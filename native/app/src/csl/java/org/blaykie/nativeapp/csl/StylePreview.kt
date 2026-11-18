package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.citation.processor.BibliographyResult
import org.blaykie.nativeapp.citation.processor.CitationCluster
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem

internal data class PreviewRow(val label: String, val parenthetical: String, val narrative: String, val bibliography: String)
internal data class StylePreview(val title: String, val rows: List<PreviewRow>)
internal object StylePreviewData {
    val items = listOf(
        CitationItem("PREVIEW1", "A sample book", listOf(CitationAuthor("Alex", "Example")), "2020", itemType = "book", publisher = "Sample Press"),
        CitationItem("PREVIEW2", "Another sample book", listOf(CitationAuthor("Alex", "Example"), CitationAuthor("Bea", "Sample")), "2021", itemType = "book", publisher = "Sample Press"),
        CitationItem("PREVIEW3", "A third sample book", listOf(CitationAuthor("Alex", "Example"), CitationAuthor("Bea", "Sample"), CitationAuthor("Casey", "Demo")), "2022", itemType = "book", publisher = "Sample Press"),
    )
    val clusters = items.flatMap { listOf(CitationCluster("${it.key}-p", listOf(it.key)), CitationCluster("${it.key}-n", listOf(it.key), mode = "narrative")) }
    fun build(title: String, result: BibliographyResult): StylePreview {
        val citations = result.citations.associateBy { it.id }
        return StylePreview(title, items.mapIndexed { index, item ->
            val entry = result.entries.single { item.key in it.itemIds }
            PreviewRow("${index + 1} author${if (index == 0) "" else "s"}",
                citations.getValue("${item.key}-p").text, citations.getValue("${item.key}-n").text,
                entry.runs.joinToString("") { it.text })
        })
    }
}
