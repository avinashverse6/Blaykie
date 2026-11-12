package org.blaykie.nativeapp.citation.processor

/** Placeholder until the debug WebView processor is available. */
internal object PlatformCitationProcessorProvider : CitationProcessorProvider {
    override fun create(styleId: String): CitationProcessor? = null
}
