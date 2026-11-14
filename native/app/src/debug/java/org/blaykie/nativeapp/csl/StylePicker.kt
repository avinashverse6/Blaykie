package org.blaykie.nativeapp.csl

internal data class StyleChoice(val id: String?, val title: String, val disabledReason: String? = null, val source: StyleSource? = null, val asset: String? = null, val originalId: String? = null, val dependent: Boolean = false, val defaultLocale: String? = null)

internal const val DEPENDENT_STYLE_NOTE = "Uses the parent style’s formatting."

/** Available dependent styles inherit their independent parent's formatting. */
internal fun styleChoices(styles: List<StyleMetadata>): List<StyleChoice> {
    val duplicateIds = styles.groupingBy(::styleSelectionId).eachCount().filterValues { it > 1 }.keys
    return listOf(StyleChoice(null, "APA 7 (built-in)")) + styles.map { style ->
        val parent = if (style.dependent) independentParent(style, styles) else null
        val effective = if (style.dependent) parent else style
        val reason = when {
            styleSelectionId(style) in duplicateIds -> "Style ID is duplicated."
            style.dependent && parent == null -> missingParentMessage(style.parentId.orEmpty())
            effective?.styleClass == "note" || effective?.citationFormat == "note" -> "Note-based styles are not supported."
            effective?.citationFormat == "numeric" -> "Numeric styles are not supported."
            effective?.styleClass != "in-text" || effective?.citationFormat != "author-date" -> "Only independent author-date styles are supported."
            else -> null
        }
        StyleChoice(styleSelectionId(style), style.title, reason, style.source, style.asset, style.id, style.dependent, style.defaultLocale)
    }
}

internal data class LanguageResolution(val tag: String, val note: String? = null)
internal fun resolveStyleLanguage(choice: StyleChoice?, selected: String, explicit: Boolean, locales: List<LocaleMetadata>, blocked: Set<String> = emptySet()): LanguageResolution {
    if (explicit) return LanguageResolution(selected)
    val wanted = choice?.defaultLocale ?: return LanguageResolution("en-US")
    if (wanted.equals("en-US", true)) return LanguageResolution("en-US")
    val found = locales.firstOrNull { it.tag.equals(wanted, true) && it.tag.lowercase() !in blocked }
    return if (found != null) LanguageResolution(found.tag) else LanguageResolution("en-US", "Language file $wanted not imported; using English (US)")
}
internal fun languageLabel(tag: String): String = if (tag.equals("en-US", true)) "English (US)" else tag
internal fun styleLanguageSummary(styleId: String?, tag: String): String = "Language: ${languageLabel(if (styleId == null) "en-US" else tag)}"
