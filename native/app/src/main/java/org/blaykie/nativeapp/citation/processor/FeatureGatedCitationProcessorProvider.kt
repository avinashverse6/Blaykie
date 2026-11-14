package org.blaykie.nativeapp.citation.processor

/** A disabled provider never invokes the factory that can construct its host. */
internal class FeatureGatedCitationProcessorProvider(
    private val enabled: Boolean,
    private val factory: (String) -> CitationProcessor?,
) : CitationProcessorProvider {
    override fun create(styleId: String): CitationProcessor? =
        if (!enabled || styleId.isBlank()) null else factory(styleId)
}
