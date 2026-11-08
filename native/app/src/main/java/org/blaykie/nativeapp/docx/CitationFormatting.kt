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
    // Unicode 14 canonical-combining-class nonzero ranges, matching Python's unicodedata.combining.
    // Some nonspacing marks have class zero and must be retained (notably in Indic scripts).
    private val combiningRanges = intArrayOf(
        0x300, 0x34E, 0x350, 0x36F, 0x483, 0x487, 0x591, 0x5BD, 0x5BF, 0x5BF, 0x5C1, 0x5C2,
        0x5C4, 0x5C5, 0x5C7, 0x5C7, 0x610, 0x61A, 0x64B, 0x65F, 0x670, 0x670, 0x6D6, 0x6DC,
        0x6DF, 0x6E4, 0x6E7, 0x6E8, 0x6EA, 0x6ED, 0x711, 0x711, 0x730, 0x74A, 0x7EB, 0x7F3,
        0x7FD, 0x7FD, 0x816, 0x819, 0x81B, 0x823, 0x825, 0x827, 0x829, 0x82D, 0x859, 0x85B,
        0x898, 0x89F, 0x8CA, 0x8E1, 0x8E3, 0x8FF, 0x93C, 0x93C, 0x94D, 0x94D, 0x951, 0x954,
        0x9BC, 0x9BC, 0x9CD, 0x9CD, 0x9FE, 0x9FE, 0xA3C, 0xA3C, 0xA4D, 0xA4D, 0xABC, 0xABC,
        0xACD, 0xACD, 0xB3C, 0xB3C, 0xB4D, 0xB4D, 0xBCD, 0xBCD, 0xC3C, 0xC3C, 0xC4D, 0xC4D,
        0xC55, 0xC56, 0xCBC, 0xCBC, 0xCCD, 0xCCD, 0xD3B, 0xD3C, 0xD4D, 0xD4D, 0xDCA, 0xDCA,
        0xE38, 0xE3A, 0xE48, 0xE4B, 0xEB8, 0xEBA, 0xEC8, 0xECB, 0xF18, 0xF19, 0xF35, 0xF35,
        0xF37, 0xF37, 0xF39, 0xF39, 0xF71, 0xF72, 0xF74, 0xF74, 0xF7A, 0xF7D, 0xF80, 0xF80,
        0xF82, 0xF84, 0xF86, 0xF87, 0xFC6, 0xFC6, 0x1037, 0x1037, 0x1039, 0x103A, 0x108D, 0x108D,
        0x135D, 0x135F, 0x1714, 0x1715, 0x1734, 0x1734, 0x17D2, 0x17D2, 0x17DD, 0x17DD, 0x18A9, 0x18A9,
        0x1939, 0x193B, 0x1A17, 0x1A18, 0x1A60, 0x1A60, 0x1A75, 0x1A7C, 0x1A7F, 0x1A7F, 0x1AB0, 0x1ABD,
        0x1ABF, 0x1ACE, 0x1B34, 0x1B34, 0x1B44, 0x1B44, 0x1B6B, 0x1B73, 0x1BAA, 0x1BAB, 0x1BE6, 0x1BE6,
        0x1BF2, 0x1BF3, 0x1C37, 0x1C37, 0x1CD0, 0x1CD2, 0x1CD4, 0x1CE0, 0x1CE2, 0x1CE8, 0x1CED, 0x1CED,
        0x1CF4, 0x1CF4, 0x1CF8, 0x1CF9, 0x1DC0, 0x1DFF, 0x20D0, 0x20DC, 0x20E1, 0x20E1, 0x20E5, 0x20F0,
        0x2CEF, 0x2CF1, 0x2D7F, 0x2D7F, 0x2DE0, 0x2DFF, 0x302A, 0x302F, 0x3099, 0x309A, 0xA66F, 0xA66F,
        0xA674, 0xA67D, 0xA69E, 0xA69F, 0xA6F0, 0xA6F1, 0xA806, 0xA806, 0xA82C, 0xA82C, 0xA8C4, 0xA8C4,
        0xA8E0, 0xA8F1, 0xA92B, 0xA92D, 0xA953, 0xA953, 0xA9B3, 0xA9B3, 0xA9C0, 0xA9C0, 0xAAB0, 0xAAB0,
        0xAAB2, 0xAAB4, 0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xAAC1, 0xAAF6, 0xAAF6, 0xABED, 0xABED,
        0xFB1E, 0xFB1E, 0xFE20, 0xFE2F, 0x101FD, 0x101FD, 0x102E0, 0x102E0, 0x10376, 0x1037A, 0x10A0D, 0x10A0D,
        0x10A0F, 0x10A0F, 0x10A38, 0x10A3A, 0x10A3F, 0x10A3F, 0x10AE5, 0x10AE6, 0x10D24, 0x10D27, 0x10EAB, 0x10EAC,
        0x10F46, 0x10F50, 0x10F82, 0x10F85, 0x11046, 0x11046, 0x11070, 0x11070, 0x1107F, 0x1107F, 0x110B9, 0x110BA,
        0x11100, 0x11102, 0x11133, 0x11134, 0x11173, 0x11173, 0x111C0, 0x111C0, 0x111CA, 0x111CA, 0x11235, 0x11236,
        0x112E9, 0x112EA, 0x1133B, 0x1133C, 0x1134D, 0x1134D, 0x11366, 0x1136C, 0x11370, 0x11374, 0x11442, 0x11442,
        0x11446, 0x11446, 0x1145E, 0x1145E, 0x114C2, 0x114C3, 0x115BF, 0x115C0, 0x1163F, 0x1163F, 0x116B6, 0x116B7,
        0x1172B, 0x1172B, 0x11839, 0x1183A, 0x1193D, 0x1193E, 0x11943, 0x11943, 0x119E0, 0x119E0, 0x11A34, 0x11A34,
        0x11A47, 0x11A47, 0x11A99, 0x11A99, 0x11C3F, 0x11C3F, 0x11D42, 0x11D42, 0x11D44, 0x11D45, 0x11D97, 0x11D97,
        0x16AF0, 0x16AF4, 0x16B30, 0x16B36, 0x16FF0, 0x16FF1, 0x1BC9E, 0x1BC9E, 0x1D165, 0x1D169, 0x1D16D, 0x1D172,
        0x1D17B, 0x1D182, 0x1D185, 0x1D18B, 0x1D1AA, 0x1D1AD, 0x1D242, 0x1D244, 0x1E000, 0x1E006, 0x1E008, 0x1E018,
        0x1E01B, 0x1E021, 0x1E023, 0x1E024, 0x1E026, 0x1E02A, 0x1E130, 0x1E136, 0x1E2AE, 0x1E2AE, 0x1E2EC, 0x1E2EF,
        0x1E8D0, 0x1E8D6, 0x1E944, 0x1E94A,
    )
    private fun pythonSortKey(value: String): String {
        val normalized = java.text.Normalizer.normalize(value.trim().lowercase(), java.text.Normalizer.Form.NFKD)
        val kept = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            var low = 0; var high = combiningRanges.size / 2 - 1; var combining = false
            while (low <= high) {
                val mid = (low + high) ushr 1; val first = combiningRanges[mid * 2]; val last = combiningRanges[mid * 2 + 1]
                if (cp < first) high = mid - 1 else if (cp > last) low = mid + 1 else { combining = true; break }
            }
            if (!combining) kept.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val text = kept.toString()
        return text.replace(Regex("[^a-z0-9]"), "").ifEmpty { text }
    }
    private fun titleSort(item: CitationItem) = sortKey(item.title.trim().replace(Regex("^(a|an|the)\\s+", RegexOption.IGNORE_CASE), ""))
    private fun namesKey(item: CitationItem): List<Pair<String, String>> = if (item.authors.isEmpty()) listOf(titleSort(item) to "") else item.authors.map { (if (it.group) pythonSortKey(it.last) else sortKey(it.last)) to sortKey(it.first) }
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
            val authorKey = item.authors.map { (if (it.group) pythonSortKey(it.last) else sortKey(it.last)) to sortKey(it.first) }
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
        fun sameName(a: String, b: String) = pythonSortKey(a).isNotEmpty() && pythonSortKey(a) == pythonSortKey(b)
        fun publisher(value: String, author: String = authors) { val p = value.trim(); if (p.isNotEmpty() && !sameName(p, author)) add("$p.") }
        fun usableUrl(): String = item.url.trim().takeUnless { value -> listOf("ebscohost", "proquest", "ezproxy", "galegroup", "gale.com", "infotrac", "lexisnexis", "libproxy", "oclc.org", "worldcat").any { it in value.lowercase() } }.orEmpty()
        fun link(allowUrl: Boolean = true) {
            val doi = item.doi.trim().ifEmpty { Regex("^\\s*DOI:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(item.extra.trim())?.groupValues?.get(1).orEmpty() }
            if (doi.isNotEmpty()) add(" https://doi.org/" + doi.replace(Regex("^(?:doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "").trim())
            else if (allowUrl && usableUrl().isNotEmpty()) add(" ${usableUrl()}")
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
        fun chapter(container: String) {
            begin("year", false)
            val editors = item.editors.map { if (it.group) it.last else "${initials(it.first)} ${it.last}".trim() }
            val editorText = when (editors.size) { 0 -> ""; 1 -> "${editors[0]} (Ed.)"; 2 -> "${editors[0]} & ${editors[1]} (Eds.)"; else -> editors.dropLast(1).joinToString(", ") + ", & ${editors.last()} (Eds.)" }
            if (container.isNotEmpty() || editorText.isNotEmpty()) {
                add("In "); if (editorText.isNotEmpty()) add(editorText + if (container.isNotEmpty()) ", " else "")
                add(container, true)
                val details = mutableListOf<String>(); if (edition().isNotEmpty()) details += edition(); if (item.volume.isNotBlank()) details += "Vol. ${item.volume.trim()}"; if (pages().isNotEmpty()) details += pages()
                if (details.isNotEmpty()) add(" (${details.joinToString(", ")})")
                add(". ")
            }
            publisher(item.publisher); link()
        }
        fun book() {
            val details = mutableListOf<String>(); if (edition().isNotEmpty()) details += edition(); if (item.volume.isNotBlank()) details += "Vol. ${item.volume.trim()}"
            begin("year", true, if (details.isEmpty()) "" else "(${details.joinToString(", ")})")
            publisher(item.publisher)
            val hasDoi = item.doi.isNotBlank() || Regex("^\\s*DOI:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(item.extra.trim())
            if (hasDoi) link(false) else if (item.publisher.isBlank()) link()
        }
        when (item.itemType) {
            "journalArticle" -> { begin("year", false); periodical(false); link() }
            "book" -> book()
            "bookSection" -> chapter(item.bookTitle.trim())
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
            "report", "document" -> {
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
                    chapter(item.proceedingsTitle.trim())
                } else if (item.conferenceName.trim().isNotEmpty()) {
                    begin("day", true, "[Paper presentation]"); add(item.conferenceName.trim() + (if (item.place.isNotBlank()) ", ${item.place.trim()}" else "") + "."); link()
                } else { begin("year", false); publisher(item.publisher, ""); link() }
            }
            "preprint" -> { begin("year", false); if (item.repository.isNotBlank()) { add(item.repository.trim(), true); add(".") }; link() }
            else -> when {
                item.publicationTitle.isNotBlank() -> { begin("year", false); periodical(false); link() }
                item.publisher.isNotBlank() -> book()
                else -> { begin("year", false); link() }
            }
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
        if (item.authors.any { it.group } || item.itemType in setOf("magazineArticle", "newspaperArticle", "webpage", "blogPost", "report", "thesis", "conferencePaper", "preprint")) return round2Reference(item, suffix)
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
