package org.blaykie.nativeapp.citation.processor

import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem

/** Asynchronous because a processor may run outside the caller's thread. */
internal interface CitationProcessor {
    suspend fun citation(
        items: List<CitationItem>, mode: String = "parenthetical", page: String = "",
        prefix: String = "", context: List<CitationItem> = items,
    ): String
}

/** Delegates directly to the existing formatter; it does not change its output. */
internal object BuiltInApaProcessor : CitationProcessor {
    override suspend fun citation(
        items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>,
    ): String = CitationFormatting.citationText(items, mode, page, prefix, context)
}

/** Platform-specific optional processors are scoped to a document session. */
internal interface CitationProcessorProvider {
    fun create(styleId: String): CitationProcessor?
}
