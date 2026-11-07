package org.blaykie.nativeapp.docx

import kotlinx.serialization.Serializable

/** Citation data shared by the DOCX core and Zotero client. */
@Serializable
data class CitationAuthor(val first: String = "", val last: String, val group: Boolean = false)
@Serializable
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
    val itemType: String = "journalArticle",
    val publisher: String = "",
    val bookTitle: String = "",
    val edition: String = "",
    val editors: List<CitationAuthor> = emptyList(),
    val url: String = "",
    val editorLead: Boolean = false,
)

internal object CitationFormatting {
    private val minorWords = setOf("a", "an", "the", "and", "but", "or", "for", "nor", "as", "at", "by", "in", "of", "off", "on", "per", "to", "up", "via")
    private fun titleCase(value: String): String {
        var beginning = true
        return value.trim().split(" ").joinToString(" ") { word ->
            val fixed = word.split("-").mapIndexed { index, piece ->
                val core = piece.trim { !it.isLetterOrDigit() && it != '_' }
                if (core.isEmpty()) piece
                else if (core.lowercase() in minorWords && !(beginning && index == 0)) piece.lowercase()
                else if (core.all { !it.isLetter() || it.isLowerCase() }) {
                    val position = piece.indexOf(core.first())
                    piece.substring(0, position) + piece.substring(position).replaceFirstChar { it.titlecase() }
                } else piece
            }.joinToString("-")
            beginning = word.endsWith(":") || word.endsWith("?") || word.endsWith(".") || word.endsWith("!") || word.endsWith("—")
            fixed
        }
    }
    private fun year(item: CitationItem): String {
        if (item.date.contains("in press", true) || item.date.contains("forthcoming", true)) return "in press"
        return Regex("\\b(1\\d{3}|20\\d{2})\\b").find(item.date)?.value ?: "n.d."
    }
    fun displayYear(item: CitationItem): String = year(item)
    private fun sortKey(value: String): String {
        val normalized = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return normalized.replace(Regex("[^a-z0-9]"), "").ifEmpty { normalized }
    }
    private fun titleSort(item: CitationItem) = sortKey(item.title.replace(Regex("^(a|an|the)\\s+", RegexOption.IGNORE_CASE), ""))
    internal fun ordered(items: List<CitationItem>) = items.distinctBy { it.key }.sortedWith(
        compareBy<CitationItem> { sortKey(it.authors.firstOrNull()?.last ?: "").ifEmpty { titleSort(it) } }
            .thenBy { sortKey(it.authors.firstOrNull()?.first ?: "") }
            .thenBy { year(it).toIntOrNull() ?: if (year(it) == "in press") 99999 else 0 }
            .thenBy { titleSort(it) }
    )
    private fun label(item: CitationItem, joiner: String = "&"): String = when (item.authors.size) {
        0 -> {
            val title = titleCase(item.title.ifBlank { "Untitled" }).split(Regex("\\s+")).take(6).joinToString(" ").trimEnd('.', ',', ':', ';')
            if (item.itemType in setOf("journalArticle", "bookSection", "newspaperArticle", "magazineArticle", "blogPost", "conferencePaper", "encyclopediaArticle", "dictionaryEntry", "preprint", "forumPost")) "\"$title\"" else title
        }
        1 -> item.authors[0].last
        2 -> "${item.authors[0].last} $joiner ${item.authors[1].last}"
        else -> "${item.authors[0].last} et al."
    }
    internal fun suffixes(items: List<CitationItem>): Map<String, String> {
        val groups = items.distinctBy { it.key }.groupBy { item ->
            val authorKey = item.authors.map { sortKey(it.last) to sortKey(it.first) }
            Triple(if (authorKey.isEmpty()) listOf("title" to titleSort(item)) else authorKey, year(item), item.authors.isEmpty())
        }
        val result = mutableMapOf<String, String>()
        groups.values.filter { it.size > 1 }.forEach { group ->
            group.sortedBy { titleSort(it) }.forEachIndexed { i, item -> result[item.key] = ('a' + (i % 26)).toString() }
        }
        return result
    }
    fun inText(items: List<CitationItem>, context: List<CitationItem> = items): String {
        if (items.isEmpty()) return ""
        val suffixes = suffixes(context)
        val groups = linkedMapOf<String, MutableList<String>>()
        for (item in ordered(items)) {
            val suffix = suffixes[item.key].orEmpty()
            val date = year(item) + (if (suffix.isNotEmpty() && year(item) in setOf("n.d.", "in press")) "-" else "") + suffix
            groups.getOrPut(label(item)) { mutableListOf() }.add(date)
        }
        return groups.entries.joinToString("; ", "(", ")") { (label, years) ->
            val joined = years.joinToString(", ")
            if (label.endsWith('"')) "${label.dropLast(1)},\" $joined" else "$label, $joined"
        }
    }

    fun citationText(items: List<CitationItem>, style: String = "parenthetical", page: String = "", prefix: String = "", context: List<CitationItem> = items): String {
        val chosen = items.distinctBy { it.key }
        if (chosen.isEmpty()) return ""
        val pages = page.trim().replace(Regex("\\s*[-‐‑‒–—]+\\s*"), "–")
        val pageText = if (pages.isEmpty()) "" else (if ('–' in pages || ',' in pages) "pp. " else "p. ") + pages
        if (style == "narrative") {
            val suffixes = suffixes(context)
            return ordered(chosen).joinToString("; ") { item ->
                val suffix = suffixes[item.key].orEmpty()
                val yr = year(item) + (if (suffix.isNotEmpty() && year(item) in setOf("n.d.", "in press")) "-" else "") + suffix
                val locator = if (chosen.size == 1 && pageText.isNotEmpty()) ", $pageText" else ""
                "${label(item, "and")} ($yr$locator)"
            }
        }
        var inner = inText(chosen, context).removePrefix("(").removeSuffix(")")
        if (chosen.size == 1 && pageText.isNotEmpty()) inner += ", $pageText"
        if (prefix.isNotEmpty()) inner = prefix.trim() + " " + inner
        return "($inner)"
    }

    fun referenceList(items: List<CitationItem>): String {
        val suffixes = suffixes(items)
        return ordered(items).joinToString("\n\n") { reference(it, suffixes[it.key].orEmpty()).joinToString("") { part -> part.first }.trim() }
    }

    fun reference(item: CitationItem, suffix: String = ""): List<Pair<String, Boolean>> {
        val names = item.authors.map { author ->
            val initials = author.first.trim().replace(".", " ").split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ") { word -> word.split('-').filter { it.isNotEmpty() }.joinToString("-") { "${it.first().uppercase()}." } }
            author.last + if (author.group || initials.isEmpty()) "" else ", $initials"
        }
        val authorList = when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> names.joinToString(", & ")
            in 3..20 -> names.dropLast(1).joinToString(", ") + ", & " + names.last()
            else -> names.take(19).joinToString(", ") + ", . . . " + names.last()
        }
        val authors = authorList + if (item.editorLead && authorList.isNotEmpty()) (if (names.size == 1) " (Ed.)" else " (Eds.)") else ""
        val year = year(item) + (if (suffix.isNotEmpty() && year(item) in setOf("n.d.", "in press")) "-" else "") + suffix
        val title = item.title.ifBlank { "Untitled" }.let { if (it.last() in ".?!") it else "$it." }
        val parts = mutableListOf<Pair<String, Boolean>>()
        if (item.itemType == "book" || item.itemType == "bookSection") {
            val authorPrefix = if (authors.isEmpty()) "" else "$authors ($year). "
            parts += authorPrefix to false
            val edition = item.edition.trim().toIntOrNull()?.let { n ->
                if (n <= 1) "" else "$n${if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }} ed."
            } ?: item.edition.trim()
            val rawTitle = item.title.ifBlank { "Untitled" }.trimEnd('.', '?', '!')
            parts += rawTitle to (item.itemType == "book")
            if (item.itemType == "book" && edition.isNotEmpty()) parts += " ($edition)." to false else parts += "." to false
            if (authors.isEmpty()) parts += " ($year)." to false
            parts += " " to false
            if (item.itemType == "bookSection" && (item.editors.isNotEmpty() || item.bookTitle.isNotBlank())) {
                val editors = item.editors.map { author ->
                    val initials = author.first.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { "${it.first().uppercase()}." }
                    if (author.group) author.last else "$initials ${author.last}".trim()
                }
                val editorText = when (editors.size) {
                    0 -> ""
                    1 -> "${editors[0]} (Ed.)"
                    2 -> "${editors[0]} & ${editors[1]} (Eds.)"
                    else -> editors.dropLast(1).joinToString(", ") + ", & ${editors.last()} (Eds.)"
                }
                parts += ("In " + editorText + if (editorText.isNotEmpty() && item.bookTitle.isNotBlank()) ", " else "") to false
                parts += item.bookTitle to true
                val detail = mutableListOf<String>()
                if (edition.isNotEmpty()) detail += edition
                if (item.volume.isNotEmpty()) detail += "Vol. ${item.volume}"
                if (item.pages.isNotEmpty()) {
                    val pages = item.pages.replace(Regex("\\s*[-‐‑‒–—]+\\s*"), "–")
                    detail += (if ('–' in pages || ',' in pages) "pp. " else "p. ") + pages
                }
                if (detail.isNotEmpty()) parts += " (${detail.joinToString(", ")})" to false
                parts += ". " to false
            }
            if (item.publisher.isNotBlank()) parts += "${item.publisher.trim()}." to false
            if (item.doi.isNotBlank()) parts += " https://doi.org/${item.doi.trim().replace(Regex("^(doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "")}" to false
            else if (item.itemType == "bookSection" && item.url.isNotBlank()) parts += " ${item.url}" to false
            return parts
        }
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
