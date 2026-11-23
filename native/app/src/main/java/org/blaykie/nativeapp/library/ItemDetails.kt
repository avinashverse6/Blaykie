package org.blaykie.nativeapp.library

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.zotero.ZoteroItem

internal data class DetailField(val label: String, val value: String)

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/** "seriesEditor" -> "Series editor", "author" -> "Author". */
internal fun roleLabel(creatorType: String): String {
    val spaced = creatorType.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase()
    return spaced.replaceFirstChar { it.uppercase() }
}

internal fun typeLabel(itemType: String): String = roleLabel(itemType)

/** Creators with roles, e.g. "Ada Lovelace (Author)". */
internal fun creatorLines(item: ZoteroItem): List<String> =
    (item.data["creators"] as? JsonArray).orEmpty().mapNotNull { element ->
        val creator = element as? JsonObject ?: return@mapNotNull null
        val name = listOf(creator.text("firstName"), creator.text("lastName")).filter { it.isNotEmpty() }
            .joinToString(" ").ifEmpty { creator.text("name") }
        if (name.isEmpty()) null
        else {
            val role = creator.text("creatorType")
            if (role.isEmpty()) name else "$name (${roleLabel(role)})"
        }
    }

/** Ordered read-only detail fields; fields the item does not have are skipped entirely. */
internal fun itemDetailFields(item: ZoteroItem, collectionNames: Map<String, String> = emptyMap()): List<DetailField> {
    val data = item.data
    val fields = mutableListOf<DetailField>()
    fun add(label: String, value: String) { if (value.isNotBlank()) fields += DetailField(label, value) }
    add("Type", typeLabel(data.text("itemType")))
    add("Title", data.text("title"))
    creatorLines(item).takeIf { it.isNotEmpty() }?.let { fields += DetailField("Creators", it.joinToString("\n")) }
    add("Date", data.text("date"))
    add("Publication", data.text("publicationTitle"))
    add("Volume", data.text("volume"))
    add("Issue", data.text("issue"))
    add("Pages", data.text("pages"))
    add("Publisher", data.text("publisher"))
    add("Place", data.text("place"))
    add("DOI", data.text("DOI"))
    add("URL", data.text("url"))
    add("ISBN", data.text("ISBN"))
    add("ISSN", data.text("ISSN"))
    add("Abstract", data.text("abstractNote"))
    add("Language", data.text("language"))
    add("Extra", data.text("extra"))
    val tags = (data["tags"] as? JsonArray).orEmpty().mapNotNull { element ->
        ((element as? JsonObject)?.text("tag"))?.takeIf { it.isNotBlank() }
    }
    if (tags.isNotEmpty()) fields += DetailField("Tags", tags.joinToString(", "))
    val collections = (data["collections"] as? JsonArray).orEmpty().mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.let { key -> collectionNames[key] }
    }
    if (collections.isNotEmpty()) fields += DetailField("Collections", collections.joinToString(", "))
    item.numChildren?.takeIf { it > 0 }?.let { fields += DetailField("Attachments and notes", "$it") }
    return fields
}

/** The item's bare DOI (any doi:/URL prefix stripped), when it has one. */
internal fun doiText(item: ZoteroItem): String? {
    val doi = item.data.text("DOI").replace(Regex("^(?:doi:\\s*|https?://(?:dx\\.)?doi\\.org/)", RegexOption.IGNORE_CASE), "").trim()
    return doi.ifEmpty { null }
}

/** https://doi.org link for the item's DOI, when it has one. */
internal fun doiLink(item: ZoteroItem): String? = doiText(item)?.let { "https://doi.org/$it" }

/** The DOI link if present, else the item URL — only for http/https, so it can open in a browser. */
internal fun externalLink(item: ZoteroItem): String? =
    doiLink(item) ?: item.data.text("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }
