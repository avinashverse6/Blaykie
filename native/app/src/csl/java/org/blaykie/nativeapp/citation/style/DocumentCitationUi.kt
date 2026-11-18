package org.blaykie.nativeapp.citation.style

import org.blaykie.nativeapp.OptionalSelectionState
import org.blaykie.nativeapp.csl.StyleChoice

internal enum class CitationBanner { None, Preparing, Failed }

internal fun citationBanner(enabled: Boolean, configuredStyle: String?, sessionStyle: String?, preparing: Boolean, failedStyle: String?): CitationBanner = when {
    !enabled || configuredStyle == null -> CitationBanner.None
    preparing && sessionStyle != null -> CitationBanner.Preparing
    failedStyle == configuredStyle -> CitationBanner.Failed
    else -> CitationBanner.None
}

internal fun citationSubtitle(enabled: Boolean, sessionStyle: String?, ready: Boolean, language: String, choices: List<StyleChoice>): String? {
    if (!enabled || sessionStyle == null || !ready) return null
    val title = choices.firstOrNull { it.id == sessionStyle && it.disabledReason == null }?.title ?: return null
    return if (language.equals("en", true) || language.startsWith("en-", true)) title else "$title · $language"
}

internal fun dismissStyleMessage(value: OptionalSelectionState): OptionalSelectionState =
    value.copy(styleMessage = null, styleMessageDismissedFor = value.styleId)
internal fun dismissLanguageMessage(value: OptionalSelectionState): OptionalSelectionState =
    value.copy(languageMessage = null, languageMessageDismissedFor = value.languageTag)
