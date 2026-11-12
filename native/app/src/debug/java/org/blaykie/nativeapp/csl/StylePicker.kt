package org.blaykie.nativeapp.csl

internal data class StyleChoice(val id: String?, val title: String, val disabledReason: String? = null)

/** Only independent, in-text author-date styles can be previewed in this step. */
internal fun styleChoices(styles: List<StyleMetadata>): List<StyleChoice> {
    val duplicateIds = styles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    return listOf(StyleChoice(null, "APA 7 (built-in)")) + styles.map { style ->
        val reason = when {
            style.id in duplicateIds -> "Style ID is duplicated."
            style.dependent -> "Dependent styles are not supported."
            style.styleClass == "note" || style.citationFormat == "note" -> "Note-based styles are not supported."
            style.citationFormat == "numeric" -> "Numeric styles are not supported."
            style.styleClass != "in-text" || style.citationFormat != "author-date" -> "Only independent author-date styles are supported."
            else -> null
        }
        StyleChoice(style.id, style.title, reason)
    }
}
