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
    val extra: String = "",
    val websiteType: String = "",
    val websiteTitle: String = "",
    val blogTitle: String = "",
    val reportNumber: String = "",
    val reportType: String = "",
    val institution: String = "",
    val thesisType: String = "",
    val university: String = "",
    val archive: String = "",
    val proceedingsTitle: String = "",
    val conferenceName: String = "",
    val place: String = "",
    val repository: String = "",
)

internal object CitationFormatting {
    private val minorWords = setOf("a", "an", "the", "and", "but", "or", "for", "nor", "as", "at", "by", "in", "of", "off", "on", "per", "to", "up", "via")
    private fun titleCase(value: String, capitalizeWords: Boolean = true): String {
        var beginning = true
        return value.trim().split(" ").joinToString(" ") { word ->
            val fixed = word.split("-").mapIndexed { index, piece ->
                val core = piece.trim { !it.isLetterOrDigit() && it != '_' }
                if (core.isEmpty()) piece
                else if (core.lowercase() in minorWords && !(beginning && index == 0)) piece.lowercase()
                else if (capitalizeWords && core.all { !it.isLetter() || it.isLowerCase() }) {
                    val position = piece.indexOf(core.first())
                    piece.substring(0, position) + piece.substring(position).replaceFirstChar { it.titlecase() }
                } else piece
            }.joinToString("-")
            beginning = word.endsWith(":") || word.endsWith("?") || word.endsWith(".") || word.endsWith("!") || word.endsWith("—")
            fixed
        }
    }
    private data class ParsedDate(val year: Int?, val month: Int? = null, val day: Int? = null, val label: String = "")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private fun parseDate(value: String): ParsedDate {
        val text = value.trim(); val lower = text.lowercase()
        if (text.isEmpty() || lower in setOf("n.d.", "nd", "no date", "undated")) return ParsedDate(null, label = "n.d.")
        if ("in press" in lower || "forthcoming" in lower) return ParsedDate(null, label = "in press")
        val iso = Regex("\\b(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?\\b").find(text)
        if (iso != null) {
            val month = iso.groupValues[2].toInt()
            val day = iso.groupValues[3].toIntOrNull()?.takeIf { it in 1..31 }
            if (month in 1..12) return ParsedDate(iso.groupValues[1].toInt(), month, day)
        }
        val match = Regex("\\b(1\\d{3}|20\\d{2})\\b").find(text) ?: return ParsedDate(null, label = "n.d.")
        val rest = text.substring(0, match.range.first) + " " + text.substring(match.range.last + 1)
        val month = Regex("[A-Za-z]+").findAll(rest).mapNotNull { word ->
            val w = word.value.lowercase()
            val i = months.indexOfFirst { it.lowercase() == w || it.take(3).lowercase() == w || (it == "September" && w == "sept") }
            if (i < 0) null else i + 1
        }.firstOrNull()
        val day = if (month == null) null else Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b").find(rest)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }
        return ParsedDate(match.value.toInt(), month, day)
    }
    private fun dateText(item: CitationItem, level: String = "year", suffix: String = ""): String {
        val date = parseDate(item.date)
        if (date.year == null) return date.label + if (suffix.isEmpty()) "" else "-$suffix"
        var text = "${date.year}$suffix"
        if (level in setOf("month", "day") && date.month != null) {
            text += ", ${months[date.month - 1]}"
            if (level == "day" && date.day != null) text += " ${date.day}"
        }
        return text
    }
    private fun year(item: CitationItem): String = dateText(item)
    fun displayYear(item: CitationItem): String = year(item)
    private fun sortKey(value: String): String {
        val normalized = java.text.Normalizer.normalize(value.trim().lowercase(), java.text.Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return normalized.replace(Regex("[^a-z0-9]"), "").ifEmpty { normalized }
    }
    private fun titleSort(item: CitationItem) = sortKey(item.title.trim().replace(Regex("^(a|an|the)\\s+", RegexOption.IGNORE_CASE), ""))
    private fun namesKey(item: CitationItem): List<Pair<String, String>> = if (item.authors.isEmpty()) listOf(titleSort(item) to "") else item.authors.map { sortKey(it.last) to sortKey(it.first) }
    private fun compareNames(a: List<Pair<String, String>>, b: List<Pair<String, String>>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val last = a[i].first.compareTo(b[i].first); if (last != 0) return last
            val first = a[i].second.compareTo(b[i].second); if (first != 0) return first
        }
        return a.size.compareTo(b.size)
    }
    internal fun ordered(items: List<CitationItem>) = items.distinctBy { it.key }.sortedWith { a, b ->
        val names = compareNames(namesKey(a), namesKey(b))
        if (names != 0) names else {
            fun yearOrder(item: CitationItem): Int = parseDate(item.date).let { it.year ?: if (it.label == "in press") 99999 else 0 }
            val years = yearOrder(a).compareTo(yearOrder(b))
            if (years != 0) years else titleSort(a).compareTo(titleSort(b))
        }
    }
    private fun initials(value: String): String = value.trim().replace(".", " ").split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.joinToString(" ") { word -> word.split('-').filter { it.isNotEmpty() }.joinToString("-") { part ->
            String(Character.toChars(part.codePointAt(0))).uppercase() + "."
        } }
    private fun surnamesNeedingInitials(items: List<CitationItem>): Set<String> {
        val seen = mutableMapOf<String, MutableSet<String>>()
        items.distinctBy { it.key }.forEach { item ->
            val first = item.authors.firstOrNull()
            if (first != null && !first.group) seen.getOrPut(sortKey(first.last)) { mutableSetOf() }.add(initials(first.first))
        }
        return seen.filterValues { it.size > 1 }.keys
    }
    private fun label(item: CitationItem, joiner: String = "&", flagged: Set<String> = emptySet()): String {
        if (item.authors.isEmpty()) {
            val title = titleCase(item.title.ifBlank { "Untitled" }).split(Regex("\\s+")).take(6).joinToString(" ").trimEnd('.', ',', ':', ';')
            return if (item.itemType in setOf("journalArticle", "bookSection", "newspaperArticle", "magazineArticle", "blogPost", "conferencePaper", "encyclopediaArticle", "dictionaryEntry", "preprint", "forumPost")) "\"$title\"" else title
        }
        val first = item.authors[0]
        val name = if (!first.group && first.first.isNotEmpty() && sortKey(first.last) in flagged) "${initials(first.first)} ${first.last}" else first.last
        return when (item.authors.size) {
            1 -> name
            2 -> "$name $joiner ${item.authors[1].last}"
            else -> "$name et al."
        }
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
        val pool = context.ifEmpty { items }
        val suffixes = suffixes(pool)
        val flagged = surnamesNeedingInitials(pool)
        val groups = linkedMapOf<String, MutableList<String>>()
        for (item in ordered(items)) {
            val suffix = suffixes[item.key].orEmpty()
            val date = dateText(item, suffix = suffix)
            groups.getOrPut(label(item, flagged = flagged)) { mutableListOf() }.add(date)
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
            val pool = context.ifEmpty { chosen }
            val suffixes = suffixes(pool)
            val flagged = surnamesNeedingInitials(pool)
            return ordered(chosen).joinToString("; ") { item ->
                val suffix = suffixes[item.key].orEmpty()
                val yr = dateText(item, suffix = suffix)
                val locator = if (chosen.size == 1 && pageText.isNotEmpty()) ", $pageText" else ""
                "${label(item, "and", flagged)} ($yr$locator)"
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

    private fun round2Reference(item: CitationItem, suffix: String): List<Pair<String, Boolean>> {
        val parts = mutableListOf<Pair<String, Boolean>>()
        fun add(text: String, italic: Boolean = false) { if (text.isNotEmpty()) parts.add(text to italic) }
        val names = item.authors.map { a -> a.last + if (a.group || initials(a.first).isEmpty()) "" else ", ${initials(a.first)}" }
        val authorList = when (names.size) {
            0 -> ""; 1 -> names[0]; 2 -> names.joinToString(", & ")
            in 3..20 -> names.dropLast(1).joinToString(", ") + ", & " + names.last()
            else -> names.take(19).joinToString(", ") + ", . . . " + names.last()
        }
        val authors = authorList + if (item.editorLead && authorList.isNotEmpty()) if (names.size == 1) " (Ed.)" else " (Eds.)" else ""
        fun begin(level: String, italic: Boolean, extras: String = "") {
            val title = item.title.trim().ifEmpty { "Untitled" }
            val date = "(${dateText(item, level, suffix)})"
            if (authors.isNotEmpty()) add((if (authors.last() in ".?!") authors else "$authors.") + " $date. ")
            add(title, italic)
            if (extras.isNotEmpty()) add(" $extras.") else if (title.last() !in ".?!") add(".")
            if (authors.isEmpty()) add(" $date.")
            add(" ")
        }
        fun sameName(a: String, b: String) = sortKey(a).isNotEmpty() && sortKey(a) == sortKey(b)
        fun publisher(value: String, author: String = authors) { val p = value.trim(); if (p.isNotEmpty() && !sameName(p, author)) add("$p.") }
        fun usableUrl(): String = item.url.trim().takeUnless { value -> listOf("ebscohost", "proquest", "ezproxy", "galegroup", "gale.com", "infotrac", "lexisnexis", "libproxy", "oclc.org", "worldcat").any { it in value.lowercase() } }.orEmpty()
        fun link() {
            val doi = item.doi.trim().ifEmpty { Regex("^\\s*DOI:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(item.extra.trim())?.groupValues?.get(1).orEmpty() }
            if (doi.isNotEmpty()) add(" https://doi.org/" + doi.replace(Regex("^(?:doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "").trim())
            else if (usableUrl().isNotEmpty()) add(" ${usableUrl()}")
        }
        fun pages(prefix: Boolean = true): String {
            val p = item.pages.trim().replace(Regex("\\s*[-‐‑‒–—]+\\s*"), "–")
            return if (p.isEmpty() || !prefix) p else (if ('–' in p || ',' in p) "pp. " else "p. ") + p
        }
        fun edition(): String {
            var text = item.edition.trim(); if (text.isEmpty()) return ""
            val low = text.lowercase().trimEnd('.').replace(Regex("\\s*(edition|ed)$"), "").trim()
            val words = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
            val number = low.toIntOrNull() ?: Regex("(\\d+)(?:st|nd|rd|th)").matchEntire(low)?.groupValues?.get(1)?.toIntOrNull() ?: words.indexOf(low).let { if (it < 0) null else it + 1 }
            if (number != null) {
                if (number == 1) return ""
                val suffix = if (number % 100 in 10..20) "th" else when (number % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
                return "$number$suffix ed."
            }
            text = text.replace(Regex("\\s*edition$", RegexOption.IGNORE_CASE), "").trim()
            if (text.lowercase().trimEnd('.') in setOf("rev", "revised")) return "Rev. ed."
            if (!Regex("\\bed\\.?$", RegexOption.IGNORE_CASE).containsMatchIn(text)) text += " ed."
            else if (!text.endsWith('.')) text += "."
            return text
        }
        fun periodical(prefix: Boolean) {
            val name = item.publicationTitle.trim(); if (name.isEmpty()) return
            add(if (name.uppercase() == name) name else titleCase(name, false), true)
            val volume = item.volume.trim(); val issue = item.issue.trim()
            if (volume.isNotEmpty()) { add(", "); add(volume, true); if (issue.isNotEmpty()) add("($issue)") }
            else if (issue.isNotEmpty()) add(", ($issue)")
            val raw = item.pages.trim()
            val locator = if (Regex("[A-Za-z]{1,3}\\d{3,}[A-Za-z0-9.\\-]*|\\d{5,}").matches(raw)) "Article $raw" else pages(prefix)
            if (locator.isNotEmpty()) add(", $locator")
            add(".")
        }
        when (item.itemType) {
            "magazineArticle", "newspaperArticle" -> { begin("day", false); periodical(item.itemType == "newspaperArticle"); link() }
            "webpage" -> {
                begin("day", true, item.websiteType.trim().let { if (it.isEmpty()) "" else "[$it]" })
                publisher(item.websiteTitle)
                if (usableUrl().isNotEmpty()) add(" ${usableUrl()}")
            }
            "blogPost" -> {
                begin("day", false); val blog = item.blogTitle.ifBlank { item.websiteTitle }.trim()
                if (blog.isNotEmpty()) { add(blog, true); add(".") }
                if (usableUrl().isNotEmpty()) add(" ${usableUrl()}")
            }
            "report" -> {
                val number = item.reportNumber.trim(); val type = item.reportType.trim()
                val extras = if (number.isNotEmpty()) {
                    if (Regex("^(report|rep\\.|no\\.?|publication|technical|working)", RegexOption.IGNORE_CASE).containsMatchIn(number)) "($number)"
                    else "(${if (type.isEmpty()) "Report" else titleCase(type)} No. $number)"
                } else if (type.isNotEmpty()) "[$type]" else ""
                begin("year", true, extras); publisher(item.institution.ifBlank { item.publisher }); link()
            }
            "thesis" -> {
                val type = item.thesisType.trim()
                val label = when {
                    Regex("\\b(ph\\.?\\s?d|d\\.?\\s?phil|doctor\\w*|dissertation)\\b", RegexOption.IGNORE_CASE).containsMatchIn(type) -> "Doctoral dissertation"
                    Regex("\\b(master\\w*|m\\.?\\s?phil|m\\.?\\s?sc|m\\.?\\s?a|m\\.?\\s?ed|mba|m\\.?\\s?tech)\\b", RegexOption.IGNORE_CASE).containsMatchIn(type) -> "Master's thesis"
                    type.isEmpty() -> "Thesis"
                    else -> type.replaceFirstChar { it.uppercase() }
                }
                val university = item.university.ifBlank { item.institution }.trim(); val archive = item.archive.trim()
                val published = usableUrl().isNotEmpty() || archive.isNotEmpty()
                val extras = if (published) "[$label${if (university.isNotEmpty()) ", $university" else ""}]" else "[Unpublished ${label.replaceFirstChar { it.lowercase() }}]"
                begin("year", true, extras)
                if (published) { if (archive.isNotEmpty()) add("$archive."); link() } else publisher(university)
            }
            "conferencePaper" -> {
                if (item.proceedingsTitle.trim().isNotEmpty()) {
                    begin("year", false)
                    val editors = item.editors.map { if (it.group) it.last else "${initials(it.first)} ${it.last}".trim() }
                    val editorText = when (editors.size) { 0 -> ""; 1 -> "${editors[0]} (Ed.)"; 2 -> "${editors[0]} & ${editors[1]} (Eds.)"; else -> editors.dropLast(1).joinToString(", ") + ", & ${editors.last()} (Eds.)" }
                    add("In "); if (editorText.isNotEmpty()) add("$editorText, "); add(item.proceedingsTitle.trim(), true)
                    val details = mutableListOf<String>(); if (edition().isNotEmpty()) details += edition(); if (item.volume.isNotBlank()) details += "Vol. ${item.volume.trim()}"; if (pages().isNotEmpty()) details += pages()
                    if (details.isNotEmpty()) add(" (${details.joinToString(", ")})")
                    add(". "); publisher(item.publisher); link()
                } else if (item.conferenceName.trim().isNotEmpty()) {
                    begin("day", true, "[Paper presentation]"); add(item.conferenceName.trim() + (if (item.place.isNotBlank()) ", ${item.place.trim()}" else "") + "."); link()
                } else { begin("year", false); publisher(item.publisher, ""); link() }
            }
            "preprint" -> { begin("year", false); if (item.repository.isNotBlank()) { add(item.repository.trim(), true); add(".") }; link() }
        }
        val merged = mutableListOf<Pair<String, Boolean>>()
        for ((text, italic) in parts) {
            if (merged.isNotEmpty() && merged.last().second == italic) merged[merged.lastIndex] = (merged.last().first + text) to italic else merged.add(text to italic)
        }
        return merged.mapIndexed { index, (text, italic) ->
            var t = text.replace(Regex(" {2,}"), " ")
            if (index == 0) t = t.trimStart(); if (index == merged.lastIndex) t = t.trimEnd()
            t to italic
        }.filter { it.first.isNotEmpty() }
    }

    fun reference(item: CitationItem, suffix: String = ""): List<Pair<String, Boolean>> {
        if (item.itemType in setOf("magazineArticle", "newspaperArticle", "webpage", "blogPost", "report", "thesis", "conferencePaper", "preprint")) return round2Reference(item, suffix)
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
