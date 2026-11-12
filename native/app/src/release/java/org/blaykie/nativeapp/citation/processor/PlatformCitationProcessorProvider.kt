package org.blaykie.nativeapp.citation.processor

/** Release offers no optional citation processors. */
internal object PlatformCitationProcessorProvider : CitationProcessorProvider {
    override fun create(styleId: String): CitationProcessor? = null
}
