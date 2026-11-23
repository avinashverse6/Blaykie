package org.blaykie.nativeapp.library

import org.blaykie.nativeapp.crossref.DraftCreator
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.zotero.SchemaField
import org.blaykie.nativeapp.zotero.TypeSchema
import org.blaykie.nativeapp.zotero.hiddenSchemaFields
import org.blaykie.nativeapp.zotero.knownBaseFields
import org.blaykie.nativeapp.zotero.titleFieldByType
import java.util.Calendar

/** Today's date as yyyy-MM-dd, for the accessDate default. */
internal fun todayIsoDate(): String {
    val now = Calendar.getInstance()
    return "%04d-%02d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
}

/** The "Common" group of the type chooser, in display order. */
internal val commonTypeOrder = listOf(
    "journalArticle", "book", "bookSection", "conferencePaper", "thesis", "report", "webpage",
    "newspaperArticle", "magazineArticle", "blogPost", "encyclopediaArticle", "presentation",
    "interview", "preprint", "dataset", "document",
)

internal val commonTypeHints = mapOf(
    "journalArticle" to "An article in a scholarly journal",
    "book" to "A whole book",
    "bookSection" to "One chapter in an edited book",
    "conferencePaper" to "A paper in conference proceedings",
    "thesis" to "A thesis or dissertation",
    "report" to "A report from an institution or agency",
    "webpage" to "A page on a website",
    "newspaperArticle" to "An article in a newspaper",
    "magazineArticle" to "An article in a magazine",
    "blogPost" to "A post on a blog",
    "encyclopediaArticle" to "An entry in an encyclopedia",
    "presentation" to "Slides or a talk at a meeting",
    "interview" to "An interview with someone",
    "preprint" to "A draft shared before journal publication",
    "dataset" to "A data set",
    "document" to "Any other document",
)

/** Splits the schema type list into the Common group (fixed order) and More types (alphabetical). */
internal fun groupedTypes(
    types: List<org.blaykie.nativeapp.zotero.ItemTypeInfo>,
): Pair<List<org.blaykie.nativeapp.zotero.ItemTypeInfo>, List<org.blaykie.nativeapp.zotero.ItemTypeInfo>> {
    val byType = types.associateBy { it.type }
    val common = commonTypeOrder.mapNotNull { byType[it] }
    val commonSet = commonTypeOrder.toSet()
    val more = types.filter { it.type !in commonSet }.sortedBy { it.label.lowercase() }
    return common to more
}

/**
 * The classic (Zotero 7-era) fields of the Common types. A field of a Common type
 * that is not in its classic set (for example DOI on a web page, or publisher on a
 * journal article, both added by newer Zotero) is shown under "More fields".
 * Types not listed here show all their fields in the main list.
 */
internal val classicFieldsByType: Map<String, Set<String>> = mapOf(
    "journalArticle" to setOf("title", "abstractNote", "publicationTitle", "volume", "issue", "pages", "date",
        "series", "seriesTitle", "seriesText", "journalAbbreviation", "language", "DOI", "ISSN", "shortTitle",
        "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "book" to setOf("title", "abstractNote", "series", "seriesNumber", "volume", "numberOfVolumes", "edition",
        "place", "publisher", "date", "numPages", "language", "ISBN", "shortTitle", "url", "accessDate",
        "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "bookSection" to setOf("title", "abstractNote", "bookTitle", "series", "seriesNumber", "volume", "edition",
        "place", "publisher", "date", "pages", "language", "ISBN", "shortTitle", "url", "accessDate", "archive",
        "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "conferencePaper" to setOf("title", "abstractNote", "proceedingsTitle", "conferenceName", "place",
        "publisher", "date", "volume", "pages", "DOI", "ISBN", "ISSN", "shortTitle", "url", "accessDate",
        "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "thesis" to setOf("title", "abstractNote", "thesisType", "university", "place", "date", "numPages",
        "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber",
        "rights", "extra"),
    "report" to setOf("title", "abstractNote", "reportNumber", "reportType", "seriesTitle", "place",
        "institution", "date", "pages", "shortTitle", "url", "accessDate", "archive", "archiveLocation",
        "libraryCatalog", "callNumber", "rights", "extra"),
    "webpage" to setOf("title", "abstractNote", "websiteTitle", "websiteType", "date", "language", "shortTitle",
        "url", "accessDate", "rights", "extra"),
    "newspaperArticle" to setOf("title", "abstractNote", "publicationTitle", "place", "date", "section",
        "edition", "pages", "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog",
        "callNumber", "rights", "extra"),
    "magazineArticle" to setOf("title", "abstractNote", "publicationTitle", "volume", "issue", "pages", "date",
        "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber",
        "rights", "extra"),
    "blogPost" to setOf("title", "abstractNote", "blogTitle", "websiteType", "date", "shortTitle", "url",
        "accessDate", "rights", "extra"),
    "encyclopediaArticle" to setOf("title", "abstractNote", "encyclopediaTitle", "series", "seriesNumber",
        "volume", "edition", "place", "publisher", "date", "pages", "ISBN", "shortTitle", "url", "accessDate",
        "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "presentation" to setOf("title", "abstractNote", "presentationType", "date", "meetingName", "place",
        "shortTitle", "url", "accessDate", "rights", "extra"),
    "interview" to setOf("title", "abstractNote", "date", "interviewMedium", "language", "shortTitle", "url",
        "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "preprint" to setOf("title", "abstractNote", "repository", "archiveID", "date", "DOI", "shortTitle", "url",
        "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
    "dataset" to setOf("title", "abstractNote", "identifier", "versionNumber", "date", "repository", "DOI",
        "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber",
        "rights", "extra"),
    "document" to setOf("title", "abstractNote", "publisher", "date", "language", "shortTitle", "url",
        "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"),
)

private val mainPublicationFields = listOf(
    "publicationTitle", "bookTitle", "encyclopediaTitle", "dictionaryTitle", "websiteTitle", "blogTitle",
    "proceedingsTitle", "conferenceName", "meetingName", "institution", "university", "publisher", "place",
    "series", "seriesTitle", "seriesNumber", "seriesText", "edition", "thesisType", "reportType",
    "reportNumber", "presentationType", "interviewMedium", "websiteType", "repository", "genre",
    "legislativeBody", "session", "billNumber", "code", "codeVolume", "codePages", "section", "history",
    "identifier", "versionNumber",
)

/** The field that holds the item's title for this type (title itself, or its base-mapped name). */
internal fun titleFieldOf(schema: TypeSchema): SchemaField? {
    val visible = schema.fields.filter { it.name !in hiddenSchemaFields }
    return visible.firstOrNull { it.baseField == "title" }
        ?: visible.firstOrNull { it.name == "title" }
        ?: titleFieldByType[schema.type]?.let { wanted -> visible.firstOrNull { it.name == wanted } }
}

private fun isDateField(field: SchemaField): Boolean =
    field.name == "date" || field.baseField == "date" || knownBaseFields[field.name] == "date"

private fun fieldRank(schema: TypeSchema, field: SchemaField, titleField: SchemaField?): Int = when {
    titleField != null && field.name == titleField.name -> 0
    field.name in mainPublicationFields -> 10
    field.name in setOf("volume", "numberOfVolumes") -> 20
    field.name == "issue" -> 21
    field.name in setOf("pages", "numPages") -> 22
    isDateField(field) -> 23
    field.name == "DOI" -> 30
    field.name == "ISBN" -> 31
    field.name == "ISSN" -> 32
    field.name == "url" -> 33
    field.name == "accessDate" -> 34
    field.name == "abstractNote" -> 90
    else -> 50
}

internal data class OrderedFormFields(val main: List<SchemaField>, val more: List<SchemaField>)

/**
 * Orders a type's fields for the form (title first, main publication fields, volume,
 * issue, pages, date, identifiers, the rest, abstract last) and splits off the
 * non-classic fields of Common types into "More fields".
 */
internal fun orderedFormFields(schema: TypeSchema): OrderedFormFields {
    val titleField = titleFieldOf(schema)
    val classic = classicFieldsByType[schema.type]
    val ordered = schema.fields
        .filter { it.name !in hiddenSchemaFields }
        .mapIndexed { index, field -> Triple(field, fieldRank(schema, field, titleField), index) }
        .sortedWith(compareBy({ it.second }, { mainPublicationFields.indexOf(it.first.name).let { i -> if (i < 0) 999 else i } }, { it.third }))
        .map { it.first }
    if (classic == null) return OrderedFormFields(ordered, emptyList())
    fun isClassic(field: SchemaField): Boolean =
        field.name in classic || (field.baseField != null && field.baseField in classic) ||
            (knownBaseFields[field.name] != null && knownBaseFields[field.name] in classic)
    return OrderedFormFields(ordered.filter { isClassic(it) }, ordered.filter { !isClassic(it) })
}

/** True when the type's title (or title-mapped) field has text; types without one never block saving. */
internal fun titleSatisfied(schema: TypeSchema, values: Map<String, String>): Boolean {
    val titleField = titleFieldOf(schema) ?: return true
    return values[titleField.name].orEmpty().isNotBlank()
}

/** Field values pre-filled when a form opens (today's access date for web-like types). */
internal fun defaultFieldValues(schema: TypeSchema, today: String = todayIsoDate()): Map<String, String> {
    val wantsAccessDate = schema.type in setOf("webpage", "blogPost", "forumPost")
    return if (wantsAccessDate && schema.fields.any { it.name == "accessDate" }) mapOf("accessDate" to today)
    else emptyMap()
}

// --- Creators -----------------------------------------------------------------

/** One editable creator row of the manual form. Nothing here is persisted. */
internal data class ManualCreatorRow(
    val role: String = "author",
    val singleField: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val name: String = "",
) {
    fun isBlank(): Boolean = firstName.isBlank() && lastName.isBlank() && name.isBlank()

    /** Converts to the shared draft creator, or null for an empty row. Order is kept by the caller. */
    fun toDraftCreator(allowedRoles: Set<String>, primaryRole: String): DraftCreator? {
        val first = cleanCreatorName(firstName)
        val last = cleanCreatorName(lastName)
        val single = cleanCreatorName(name)
        if (first.isEmpty() && last.isEmpty() && single.isEmpty()) return null
        val role = if (role in allowedRoles) role else primaryRole
        return if (singleField) DraftCreator(name = single, role = role)
        else DraftCreator(firstName = first, lastName = last, role = role)
    }
}

/** Trims and collapses repeated spaces only; never touches letters, marks or capitalisation. */
internal fun cleanCreatorName(value: String): String = value.trim().replace(Regex("\\s+"), " ")

/**
 * Splits "Last, First" at the first comma (later commas stay in the first-name part,
 * which is where Zotero users commonly put a suffix such as "Jr."). Null without a comma.
 */
internal fun splitLastFirst(value: String): Pair<String, String>? {
    val comma = value.indexOf(',')
    if (comma < 0) return null
    val last = cleanCreatorName(value.substring(0, comma))
    if (last.isEmpty()) return null
    return last to cleanCreatorName(value.substring(comma + 1))
}

/** True when a one-field value looks like "Last, First" and Person mode may fit better. */
internal fun looksLikeLastFirst(value: String): Boolean {
    val parts = splitLastFirst(value) ?: return false
    return parts.second.isNotEmpty()
}

/**
 * Parses the "Paste several authors" text: split ONLY on new lines and semicolons
 * (never on "and" or "&", which organisation names contain). A part with a comma
 * becomes Last, First; a part without one becomes a single-field entry. Order is kept.
 */
internal fun parsePastedCreators(text: String): List<ManualCreatorRow> =
    text.split('\n', ';').mapNotNull { part ->
        val cleaned = cleanCreatorName(part)
        if (cleaned.isEmpty()) return@mapNotNull null
        val split = splitLastFirst(cleaned)
        if (split != null) ManualCreatorRow(singleField = false, lastName = split.first, firstName = split.second)
        else ManualCreatorRow(singleField = true, name = cleaned)
    }

/**
 * Builds the one-item draft for saving. Only fields of the type's template are sent;
 * empty optional fields are left out. Creator order is exactly the user's order.
 */
internal fun buildManualDraft(
    schema: TypeSchema,
    values: Map<String, String>,
    creators: List<ManualCreatorRow>,
): ItemDraft {
    val titleField = titleFieldOf(schema)
    val fields = LinkedHashMap<String, String>()
    schema.fields.forEach { field ->
        if (field.name in hiddenSchemaFields) return@forEach
        val value = values[field.name]?.trim().orEmpty()
        if (value.isNotEmpty()) fields[field.name] = value
    }
    // Never send both a base "title" and its type-specific name.
    if (titleField != null && titleField.name != "title") fields.remove("title")
    val allowedRoles = schema.creatorTypes.map { it.type }.toSet()
    val primary = schema.primaryCreatorType()
    val draftCreators = creators.mapNotNull { it.toDraftCreator(allowedRoles, primary) }.toMutableList()
    return ItemDraft(schema.type, fields, draftCreators)
}

/**
 * True when the form differs from a fresh form for this type (typed text, creators
 * added or edited). Used for the "Discard this item?" question.
 */
internal fun manualFormDirty(
    schema: TypeSchema?,
    values: Map<String, String>,
    creators: List<ManualCreatorRow>,
    today: String = todayIsoDate(),
): Boolean {
    if (schema == null) return values.values.any { it.isNotBlank() } || creators.any { !it.isBlank() }
    val defaults = defaultFieldValues(schema, today)
    val valueDirty = (values.keys + defaults.keys).any { name ->
        values[name].orEmpty().trim() != defaults[name].orEmpty()
    }
    if (valueDirty) return true
    val initial = listOf(ManualCreatorRow(role = schema.primaryCreatorType()))
    return creators != initial
}
