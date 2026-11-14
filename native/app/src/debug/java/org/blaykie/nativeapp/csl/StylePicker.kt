package org.blaykie.nativeapp.csl

internal data class StyleChoice(val id: String?, val title: String, val disabledReason: String? = null, val source: StyleSource? = null, val asset: String? = null, val originalId: String? = null)

/** Only independent, in-text author-date styles can be previewed in this step. */
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
        StyleChoice(styleSelectionId(style), style.title, reason, style.source, style.asset, style.id)
    }
}
