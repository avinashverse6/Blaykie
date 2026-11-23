package org.blaykie.nativeapp.crossref

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One editable creator on the review screen. Role is "author" or "editor". */
data class DraftCreator(val firstName: String = "", val lastName: String = "", val name: String = "", val role: String = "author") {
    fun displayName(): String = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { name }
}

/** A locally built Zotero item being reviewed; nothing here is persisted. */
data class ItemDraft(
    var itemType: String,
    val fields: LinkedHashMap<String, String>,
    val creators: MutableList<DraftCreator>,
    /** True when Crossref's type was unknown and journalArticle was guessed. */
    val typeGuessed: Boolean = false,
) {
    var title: String
        get() = fields["title"].orEmpty()
        set(value) { fields["title"] = value }
    fun withFields(vararg updates: Pair<String, String>): ItemDraft {
        updates.forEach { (key, value) -> if (value.isBlank()) fields.remove(key) else fields[key] = value }
        return this
    }
}

internal val supportedItemTypes = listOf("journalArticle", "book", "bookSection", "conferencePaper",
    "preprint", "report", "thesis", "webpage", "magazineArticle", "newspaperArticle")

/** Strips HTML/JATS tags and decodes common entities, collapsing whitespace. */
internal fun stripMarkup(text: String): String {
    val withoutTags = text.replace(Regex("<[^>]+>"), " ")
    return withoutTags
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("\\s+"), " ").trim()
}

private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
private fun JsonObject.firstText(name: String): String =
    ((this[name] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun issuedDate(work: JsonObject): String {
    val parts = (((work["issued"] as? JsonObject)?.get("date-parts") as? JsonArray)
        ?.firstOrNull() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        ?: return ""
    if (parts.isEmpty() || parts[0] <= 0) return ""
    return when {
        parts.size >= 3 -> "%04d-%02d-%02d".format(parts[0], parts[1], parts[2])
        parts.size == 2 -> "%04d-%02d".format(parts[0], parts[1])
        else -> parts[0].toString()
    }
}

/** Maps one Crossref work object to a Zotero item draft. Unknown fields are left out. */
fun crossrefToDraft(work: JsonObject): ItemDraft {
    val crossrefType = work.str("type")
    var guessed = false
    val itemType = when (crossrefType) {
        "journal-article" -> "journalArticle"
        "book", "monograph", "edited-book" -> "book"
        "book-chapter", "book-section" -> "bookSection"
        "proceedings-article" -> "conferencePaper"
        "posted-content" -> "preprint"
        "report" -> "report"
        "dissertation" -> "thesis"
        else -> { guessed = true; "journalArticle" }
    }
    val fields = linkedMapOf<String, String>()
    fun put(name: String, value: String) { if (value.isNotBlank()) fields[name] = value }
    put("title", stripMarkup(work.firstText("title")))
    val container = stripMarkup(work.firstText("container-title"))
    if (itemType == "bookSection") put("bookTitle", container) else put("publicationTitle", container)
    put("volume", work.str("volume"))
    put("issue", work.str("issue"))
    put("pages", work.str("page"))
    put("date", issuedDate(work))
    put("DOI", canonicalDoi(work.str("DOI")) ?: work.str("DOI"))
    put("url", work.str("URL"))
    put("ISSN", work.firstText("ISSN"))
    if (itemType in setOf("book", "bookSection")) put("ISBN", work.firstText("ISBN"))
    put("publisher", work.str("publisher"))
    put("place", work.str("publisher-location"))
    put("abstractNote", stripMarkup(work.str("abstract")))
    put("language", work.str("language"))
    put("journalAbbreviation", stripMarkup(work.firstText("short-container-title")))
    put("edition", work.str("edition-number"))
    put("series", work.str("series-title"))
    fun creators(name: String, role: String): List<DraftCreator> =
        (work[name] as? JsonArray).orEmpty().mapNotNull { element ->
            val creator = element as? JsonObject ?: return@mapNotNull null
            val given = creator.str("given"); val family = creator.str("family"); val literal = creator.str("name")
            if (given.isEmpty() && family.isEmpty() && literal.isEmpty()) null
            else DraftCreator(given, family, if (given.isEmpty() && family.isEmpty()) literal else "", role)
        }
    val creators = (creators("author", "author") + creators("editor", "editor")).toMutableList()
    return ItemDraft(itemType, fields, creators, guessed)
}

/** The one-item JSON array sent to Zotero (collections only when a collection is chosen). */
fun buildItemJson(draft: ItemDraft, collectionKey: String? = null): String = buildJsonArray {
    add(buildJsonObject {
        put("itemType", draft.itemType)
        draft.fields.forEach { (name, value) -> if (value.isNotBlank()) put(name, value) }
        put("creators", buildJsonArray {
            draft.creators.filter { it.displayName().isNotBlank() }.forEach { creator ->
                add(buildJsonObject {
                    put("creatorType", creator.role)
                    if (creator.name.isNotBlank() && creator.firstName.isBlank() && creator.lastName.isBlank()) {
                        put("name", creator.name)
                    } else {
                        put("firstName", creator.firstName); put("lastName", creator.lastName)
                    }
                })
            }
        })
        if (!collectionKey.isNullOrBlank()) put("collections", buildJsonArray { add(JsonPrimitive(collectionKey)) })
    })
}.toString()
