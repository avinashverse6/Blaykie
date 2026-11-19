package org.blaykie.nativeapp.docx

/** The ordered, formatted runs consumed by both APA export and the preview. */
internal fun apaBibliographyEntries(items: List<CitationItem>): List<List<Pair<String, Boolean>>> {
    val suffixes = CitationFormatting.suffixes(items)
    return CitationFormatting.ordered(items).map { item ->
        val merged = mutableListOf<Pair<String, Boolean>>()
        CitationFormatting.reference(item, suffixes[item.key].orEmpty()).filter { it.first.isNotEmpty() }.forEach { part ->
            if (merged.isNotEmpty() && merged.last().second == part.second) {
                val prev = merged.removeAt(merged.lastIndex); merged.add((prev.first + part.first) to part.second)
            } else merged.add(part)
        }
        merged
    }
}
