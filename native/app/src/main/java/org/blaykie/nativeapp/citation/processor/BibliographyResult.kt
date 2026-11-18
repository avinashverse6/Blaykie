package org.blaykie.nativeapp.citation.processor

/** Formatting only; no HTML is retained in the result. */
internal data class RichRun(val text: String, val italic: Boolean = false, val bold: Boolean = false,
    val underline: Boolean = false, val smallCaps: Boolean = false)
internal data class BibliographyEntry(val itemIds: List<String>, val runs: List<RichRun>)
internal data class BibliographyLayout(val hangingIndent: Boolean? = null, val lineSpacing: Double? = null, val entrySpacing: Double? = null)
internal data class CitationCluster(val id: String, val itemIds: List<String>, val mode: String = "parenthetical", val page: String = "", val prefix: String = "")
internal data class ClusterResult(val id: String, val text: String)
internal data class BibliographyResult(val entries: List<BibliographyEntry>, val layout: BibliographyLayout, val citations: List<ClusterResult>)
