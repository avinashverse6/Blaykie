package org.blaykie.nativeapp.docx

/** The journal-article formatting needed by DOCX insertion and export. */
data class CitationAuthor(val first: String = "", val last: String)
data class CitationItem(
    val key: String,
    val title: String,
    val authors: List<CitationAuthor>,
    val date: String,
    val publicationTitle: String = "",
    val volume: String = "",
    val issue: String = "",
    val pages: String = "",
    val doi: String = "",
)

internal object CitationFormatting {
    fun inText(items: List<CitationItem>): String = items.distinctBy { it.key }
        .sortedBy { it.authors.firstOrNull()?.last?.lowercase() ?: it.title.lowercase() }
        .joinToString("; ", "(", ")") { item ->
            val label = when (item.authors.size) {
                0 -> "\"${item.title}\""
                1 -> item.authors[0].last
                2 -> "${item.authors[0].last} & ${item.authors[1].last}"
                else -> "${item.authors[0].last} et al."
            }
            val year = Regex("\\b(1\\d{3}|20\\d{2})\\b").find(item.date)?.value ?: "n.d."
            if (label.endsWith('"')) "${label.dropLast(1)},\" $year" else "$label, $year"
        }

    fun reference(item: CitationItem): List<Pair<String, Boolean>> {
        val names = item.authors.map { author ->
            val initials = author.first.trim().replace(".", " ").split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ") { word -> word.split('-').filter { it.isNotEmpty() }.joinToString("-") { "${it.first().uppercase()}." } }
            author.last + if (initials.isEmpty()) "" else ", $initials"
        }
        val authors = when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> names.joinToString(", & ")
            in 3..20 -> names.dropLast(1).joinToString(", ") + ", & " + names.last()
            else -> names.take(19).joinToString(", ") + ", . . . " + names.last()
        }
        val year = Regex("\\b(1\\d{3}|20\\d{2})\\b").find(item.date)?.value ?: "n.d."
        val title = item.title.ifBlank { "Untitled" }.let { if (it.last() in ".?!") it else "$it." }
        val parts = mutableListOf<Pair<String, Boolean>>()
        parts += (if (authors.isEmpty()) "$title ($year). " else "$authors ($year). $title ") to false
        if (item.publicationTitle.isNotBlank()) {
            parts += item.publicationTitle.trim() to true
            if (item.volume.isNotBlank()) {
                parts += ", " to false
                parts += item.volume.trim() to true
                if (item.issue.isNotBlank()) parts += "(${item.issue.trim()})" to false
            } else if (item.issue.isNotBlank()) parts += ", (${item.issue.trim()})" to false
            if (item.pages.isNotBlank()) {
                val pages = item.pages.trim().replace(Regex("\\s*[-‐‑‒–—]+\\s*"), "–")
                parts += ", $pages" to false
            }
            parts += "." to false
        }
        if (item.doi.isNotBlank()) {
            val doi = item.doi.trim().replace(Regex("^(doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "")
            parts += " https://doi.org/$doi" to false
        }
        return parts
    }
}
