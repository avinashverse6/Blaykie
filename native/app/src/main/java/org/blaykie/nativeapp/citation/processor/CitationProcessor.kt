package org.blaykie.nativeapp.citation.processor

import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem

/** Asynchronous because a processor may run outside the caller's thread. */
internal interface CitationProcessor {
    suspend fun citation(
        items: List<CitationItem>, mode: String = "parenthetical", page: String = "",
        prefix: String = "", context: List<CitationItem> = items,
    ): String
    suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult
}

/** Delegates directly to the existing formatter; it does not change its output. */
internal object BuiltInApaProcessor : CitationProcessor {
    override suspend fun citation(
        items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>,
    ): String = CitationFormatting.citationText(items, mode, page, prefix, context)

    override suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult {
        require(items.map { it.key }.distinct().size == items.size) { "Duplicate item ID" }
        require(clusters.map { it.id }.distinct().size == clusters.size && clusters.all { it.id.isNotBlank() && it.itemIds.isNotEmpty() }) { "Invalid citation clusters" }
        val lookup = items.associateBy { it.key }
        val suffixes = CitationFormatting.suffixes(items)
        val entries = CitationFormatting.ordered(items).map { item ->
            val pieces = CitationFormatting.reference(item, suffixes[item.key].orEmpty()).toMutableList()
            if (pieces.isNotEmpty()) {
                pieces[0] = pieces[0].first.trimStart() to pieces[0].second
                pieces[pieces.lastIndex] = pieces.last().first.trimEnd() to pieces.last().second
            }
            BibliographyEntry(listOf(item.key), pieces.filter { it.first.isNotEmpty() }.map { RichRun(it.first, italic = it.second) })
        }
        val citations = clusters.map { cluster ->
            require(cluster.mode in setOf("parenthetical", "narrative")) { "Unsupported citation mode" }
            val selected = cluster.itemIds.map { requireNotNull(lookup[it]) { "Unknown citation item" } }
            ClusterResult(cluster.id, CitationFormatting.citationText(selected, cluster.mode, cluster.page, cluster.prefix, items))
        }
        return BibliographyResult(entries, BibliographyLayout(hangingIndent = true), citations)
    }
}

/** Platform-specific optional processors are scoped to a document session. */
internal interface CitationProcessorProvider {
    fun create(styleId: String): CitationProcessor?
}
