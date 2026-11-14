package org.blaykie.nativeapp.csl

internal data class StyleChoice(val id: String?, val title: String, val disabledReason: String? = null, val source: StyleSource? = null, val asset: String? = null, val originalId: String? = null, val dependent: Boolean = false)

internal const val DEPENDENT_STYLE_NOTE = "Uses the parent style's formatting; the dependent style's own language setting is applied once language files can be imported"

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
        StyleChoice(styleSelectionId(style), style.title, reason, style.source, style.asset, style.id, style.dependent)
    }
}
