package org.blaykie.nativeapp.citation.processor

import android.content.Context

/** The release variant provides no optional processors. */
internal class PlatformCitationProcessorProvider(@Suppress("UNUSED_PARAMETER") context: Context) : CitationProcessorProvider {
    override fun create(styleId: String): CitationProcessor? = null
}
