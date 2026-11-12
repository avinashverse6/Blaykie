package org.blaykie.nativeapp.citation.processor

import android.content.Context
import org.blaykie.nativeapp.csl.CitationItemToCsl
import org.blaykie.nativeapp.csl.narrativeAuthor
import org.blaykie.nativeapp.csl.CslHostState
import org.blaykie.nativeapp.csl.CslRenderRequest
import org.blaykie.nativeapp.csl.CslWebViewHost
import org.blaykie.nativeapp.docx.CitationItem

/** Construct once per session; conversion is replaceable for comparison. */
internal class PlatformCitationProcessorProvider(
    context: Context,
    private val requestFor: (List<CitationItem>, List<CitationItem>) -> CslRenderRequest = CitationItemToCsl::request,
) : CitationProcessorProvider {
    private val host = CslWebViewHost(context.applicationContext)

    override fun create(styleId: String): CitationProcessor? {
        if (styleId.isBlank()) return null
        return object : CitationProcessor {
            override suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult {
                val ready = host.state as? CslHostState.Ready
                if (ready?.styleId != styleId) host.warmUp(styleId).getOrThrow()
                return host.bibliography(items, clusters).getOrThrow()
            }
            override suspend fun citation(items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>): String {
                require(mode == "parenthetical" || mode == "narrative") { "Unsupported citation mode" }
                require(mode != "narrative" || items.size == 1) { "CSL narrative requires one item" }
                val request = requestFor(items, context).copy(page = page, prefix = prefix)
                request.toJson() // Reject unsupported combinations before warming the engine.
                val ready = host.state as? CslHostState.Ready
                if (ready?.styleId != styleId) host.warmUp(styleId).getOrThrow()
                val response = host.render(request).getOrThrow()
                return if (mode == "narrative") "${narrativeAuthor(response.author, response.andTerm, items.single().authors.size, items.single().authors.filter { it.group }.map { it.last })} ${response.suppress}" else response.cluster
            }
        }
    }
    fun cancel() = host.cancel()
    fun destroy() = host.destroy()
}
