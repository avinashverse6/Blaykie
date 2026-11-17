package org.blaykie.nativeapp.citation.style

import org.blaykie.nativeapp.OptionalSelectionState
import org.blaykie.nativeapp.csl.LocaleMetadata
import org.blaykie.nativeapp.csl.StyleChoice
import org.blaykie.nativeapp.csl.resolveStyleLanguage
import org.blaykie.nativeapp.session.Session

/** The stored selection stays intact on fallback so a re-import can make it usable again. */
internal fun reconcileSelections(
    stored: OptionalSelectionState, legacy: Session, adopt: Boolean,
    choices: List<StyleChoice>, locales: List<LocaleMetadata>,
): Pair<OptionalSelectionState, Session> {
    val hasLegacy = legacy.debugCitationStyleId != null || legacy.debugCitationLanguageExplicit ||
        !legacy.debugCitationLanguage.equals("en-US", ignoreCase = true)
    val selection = if (adopt && !stored.styleInitialized && stored.styleId == null && hasLegacy) {
        stored.copy(styleId = legacy.debugCitationStyleId,
            languageTag = stored.languageTag ?: legacy.debugCitationLanguage.takeIf { legacy.debugCitationLanguageExplicit },
            styleInitialized = true)
    } else stored
    val style = selection.styleId?.let { id -> choices.singleOrNull { it.id == id && it.disabledReason == null } }
    val missingStyle = selection.styleId != null && style == null
    val missingLanguage = selection.languageTag?.let { tag ->
        !tag.equals("en-US", true) && locales.none { it.tag.equals(tag, true) }
    } == true
    val effectiveLanguage = selection.languageTag.takeUnless { missingLanguage }
    val resolved = resolveStyleLanguage(style, effectiveLanguage ?: "en-US", effectiveLanguage != null, locales)
    val result = legacy.copy(
        debugCitationStyleId = if (missingStyle) null else selection.styleId,
        debugCitationLanguage = resolved.tag,
        debugCitationLanguageExplicit = effectiveLanguage != null,
    )
    return selection.copy(
        styleMessage = if (missingStyle) "Selected citation style is unavailable; using built-in APA." else null,
        languageMessage = if (missingLanguage) "Selected citation language is unavailable; using automatic language." else null,
    ) to result
}
