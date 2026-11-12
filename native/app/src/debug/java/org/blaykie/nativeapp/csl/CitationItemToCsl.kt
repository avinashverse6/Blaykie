package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import java.util.Locale

/** Converts the currently available fields; no network or DOCX metadata involved. */
internal object CitationItemToCsl {
    private val types = mapOf(
        "journalArticle" to "article-journal", "book" to "book", "bookSection" to "chapter",
        "magazineArticle" to "article-magazine", "newspaperArticle" to "article-newspaper",
        "webpage" to "webpage", "blogPost" to "post-weblog", "report" to "report",
        "thesis" to "thesis", "conferencePaper" to "paper-conference", "preprint" to "article",
    )
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun date(value: String): JsonObject? {
        val text = value.trim()
        if (text.isEmpty() || text.lowercase(Locale.ROOT) in setOf("n.d.", "nd", "no date", "undated")) return null
        if (text.contains("in press", ignoreCase = true) || text.contains("forthcoming", ignoreCase = true)) {
            return buildJsonObject { put("literal", "in press") }
        }
        val iso = Regex("^(\\d{4})(?:[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?)?$").matchEntire(text)
        val values = if (iso != null) {
            val year = iso.groupValues[1].toInt()
            val month = iso.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 }
            val day = iso.groupValues[3].toIntOrNull()?.takeIf { it in 1..31 }
            listOfNotNull(year, month, if (month == null) null else day)
        } else {
            val year = Regex("\\b(1\\d{3}|20\\d{2})\\b").find(text)?.value?.toIntOrNull()
            val month = Regex("[A-Za-z]+").findAll(text).mapNotNull { match ->
                val s = match.value.lowercase(Locale.ROOT)
                val i = months.indexOfFirst { it == s || it.take(3) == s || (it == "september" && s == "sept") }
                if (i < 0) null else i + 1
            }.firstOrNull()
            val withoutYear = text.replace(Regex("\\b(1\\d{3}|20\\d{2})\\b"), "")
            val day = if (month != null) Regex("\\b\\d{1,2}\\b").find(withoutYear)?.value?.toIntOrNull()?.takeIf { it in 1..31 } else null
            if (year == null) emptyList() else listOfNotNull(year, month, day)
        }
        return if (values.isEmpty()) buildJsonObject { put("literal", text) }
        else buildJsonObject { put("date-parts", buildJsonArray { add(buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }) }
    }

    private fun names(authors: List<CitationAuthor>): JsonArray = buildJsonArray {
        authors.forEach { author ->
            add(buildJsonObject {
                if (author.group) put("literal", author.last)
                else { if (author.last.isNotBlank()) put("family", author.last); if (author.first.isNotBlank()) put("given", author.first) }
            })
        }
    }

    fun convert(item: CitationItem): JsonObject = buildJsonObject {
        require(item.key.isNotBlank()) { "Missing citation item key" }
        put("id", item.key)
        put("type", types[item.itemType] ?: throw IllegalArgumentException("Unsupported item type: ${item.itemType}"))
        fun field(name: String, value: String) { if (value.isNotBlank()) put(name, value) }
        field("title", item.title)
        if (!item.editorLead && item.authors.isNotEmpty()) put("author", names(item.authors))
        if (item.editors.isNotEmpty()) put("editor", names(item.editors))
        date(item.date)?.let { put("issued", it) }
        field("container-title", when (item.itemType) {
            "bookSection" -> item.bookTitle
            "webpage" -> item.websiteTitle.ifBlank { item.publicationTitle }
            "blogPost" -> item.blogTitle.ifBlank { item.websiteTitle }.ifBlank { item.publicationTitle }
            "conferencePaper" -> item.proceedingsTitle.ifBlank { item.publicationTitle }
            else -> item.publicationTitle
        })
        field("volume", item.volume); field("issue", item.issue); field("page", item.pages)
        val doi = item.doi.trim().ifEmpty {
            Regex("^\\s*DOI:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(item.extra)?.groupValues?.get(1).orEmpty()
        }.replace(Regex("^(doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "").trim()
        field("DOI", doi); field("URL", item.url)
        val editionWords = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
        val wordIndex = editionWords.indexOf(item.edition.trim().lowercase(Locale.ROOT))
        field("edition", if (wordIndex >= 0) (wordIndex + 1).toString() else item.edition)
        field("publisher", when (item.itemType) {
            "report" -> item.institution.ifBlank { item.publisher }
            "thesis" -> item.university.ifBlank { item.institution }.ifBlank { item.publisher }
            "preprint" -> item.repository.ifBlank { item.publisher }
            else -> item.publisher
        })
        field("publisher-place", item.place)
        field("number", item.reportNumber)
        field("genre", when (item.itemType) {
            "report" -> item.reportType
            "thesis" -> item.thesisType
            "webpage" -> item.websiteType
            "preprint" -> "Preprint"
            else -> ""
        })
        field("event-title", item.conferenceName)
        field("event-place", item.place)
        field("archive", item.archive)
    }

    fun request(items: List<CitationItem>, context: List<CitationItem>): CslRenderRequest {
        val records = (context + items).distinctBy { it.key }.map(::convert)
        return CslRenderRequest(records, items.map { it.key })
    }
}
