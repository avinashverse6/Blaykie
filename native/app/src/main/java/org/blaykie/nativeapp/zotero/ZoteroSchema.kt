package org.blaykie.nativeapp.zotero

/** One item type offered by the Zotero schema API (attachment, note and annotation excluded). */
data class ItemTypeInfo(val type: String, val label: String)

/** One field of an item type. [baseField] is set when it maps to a Zotero base field (e.g. bookTitle -> publicationTitle). */
data class SchemaField(val name: String, val label: String, val baseField: String? = null)

/** One creator role an item type allows; exactly one is primary (listed first by the API). */
data class CreatorTypeInfo(val type: String, val label: String, val primary: Boolean = false)

/**
 * Everything the manual-add form needs for one item type: the fields of the item
 * template (these are the only names a POST may use), their labels, and the roles.
 * [basic] is true when the built-in fallback supplied this schema.
 */
data class TypeSchema(
    val type: String,
    val label: String,
    val fields: List<SchemaField>,
    val creatorTypes: List<CreatorTypeInfo>,
    val basic: Boolean = false,
) {
    fun primaryCreatorType(): String = creatorTypes.firstOrNull { it.primary }?.type
        ?: creatorTypes.firstOrNull()?.type ?: "author"
    fun field(name: String): SchemaField? = fields.firstOrNull { it.name == name }
    override fun toString(): String = "TypeSchema($type)"
}

/** Item types that are never offered for manual creation. */
internal val hiddenItemTypes = setOf("attachment", "note", "annotation")

/** Field names that exist in item JSON but are never typed by the user. */
internal val hiddenSchemaFields = setOf(
    "itemType", "key", "version", "collections", "tags", "relations", "dateAdded", "dateModified",
    "parentItem", "linkMode", "md5", "mtime", "contentType", "charset", "filename", "path",
    "complete", "deleted",
)

/** Known base-field mappings the API does not always spell out (type-specific name -> base name). */
internal val knownBaseFields = mapOf(
    "bookTitle" to "publicationTitle",
    "proceedingsTitle" to "publicationTitle",
    "encyclopediaTitle" to "publicationTitle",
    "dictionaryTitle" to "publicationTitle",
    "caseName" to "title",
    "nameOfAct" to "title",
    "subject" to "title",
    "dateDecided" to "date",
    "dateEnacted" to "date",
)

/** Types whose title-equivalent field has a different name, when no base mapping is available. */
internal val titleFieldByType = mapOf(
    "case" to "caseName",
    "statute" to "nameOfAct",
    "email" to "subject",
)

/** "journalAbbreviation" -> "Journal Abbreviation"; only a last-resort label. */
internal fun humaniseSchemaName(name: String): String {
    val spaced = name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
    return spaced.replaceFirstChar { it.uppercase() }
}

private val fallbackFieldLabels = mapOf(
    "title" to "Title", "abstractNote" to "Abstract", "publicationTitle" to "Publication",
    "bookTitle" to "Book Title", "proceedingsTitle" to "Proceedings Title",
    "encyclopediaTitle" to "Encyclopedia Title", "websiteTitle" to "Website Title",
    "blogTitle" to "Blog Title", "volume" to "Volume", "issue" to "Issue", "pages" to "Pages",
    "numPages" to "# of Pages", "numberOfVolumes" to "# of Volumes", "date" to "Date",
    "series" to "Series", "seriesTitle" to "Series Title", "seriesText" to "Series Text",
    "seriesNumber" to "Series Number", "journalAbbreviation" to "Journal Abbreviation",
    "language" to "Language", "DOI" to "DOI", "ISBN" to "ISBN", "ISSN" to "ISSN",
    "shortTitle" to "Short Title", "url" to "URL", "accessDate" to "Accessed",
    "archive" to "Archive", "archiveLocation" to "Location in Archive",
    "libraryCatalog" to "Library Catalog", "callNumber" to "Call Number", "rights" to "Rights",
    "extra" to "Extra", "edition" to "Edition", "place" to "Place", "publisher" to "Publisher",
    "conferenceName" to "Conference Name", "sessionTitle" to "Session Title",
    "thesisType" to "Type", "university" to "University", "reportNumber" to "Report Number",
    "reportType" to "Report Type", "institution" to "Institution", "websiteType" to "Website Type",
    "presentationType" to "Type", "meetingName" to "Meeting Name", "interviewMedium" to "Medium",
    "repository" to "Repository", "archiveID" to "Archive ID", "identifier" to "Identifier",
    "versionNumber" to "Version", "section" to "Section", "genre" to "Genre",
    "caseName" to "Case Name", "nameOfAct" to "Name of Act", "subject" to "Subject",
)

private val fallbackCreatorLabels = mapOf(
    "author" to "Author", "editor" to "Editor", "contributor" to "Contributor",
    "translator" to "Translator", "seriesEditor" to "Series Editor",
    "reviewedAuthor" to "Reviewed Author", "bookAuthor" to "Book Author",
    "interviewee" to "Interviewee", "interviewer" to "Interviewer", "presenter" to "Presenter",
    "recipient" to "Recipient", "director" to "Director", "artist" to "Artist",
    "composer" to "Composer", "programmer" to "Programmer", "podcaster" to "Podcaster",
    "guest" to "Guest", "cartographer" to "Cartographer", "inventor" to "Inventor",
    "performer" to "Performer", "producer" to "Producer", "sponsor" to "Sponsor",
)

/**
 * The small built-in schema used only when the Zotero schema API cannot be reached:
 * the 11 common types with their classic Zotero field names. Nothing here is persisted.
 */
internal object ZoteroSchemaFallback {
    val types: List<ItemTypeInfo> = listOf(
        ItemTypeInfo("journalArticle", "Journal Article"),
        ItemTypeInfo("book", "Book"),
        ItemTypeInfo("bookSection", "Book Chapter"),
        ItemTypeInfo("conferencePaper", "Conference Paper"),
        ItemTypeInfo("thesis", "Thesis"),
        ItemTypeInfo("report", "Report"),
        ItemTypeInfo("webpage", "Web Page"),
        ItemTypeInfo("newspaperArticle", "Newspaper Article"),
        ItemTypeInfo("magazineArticle", "Magazine Article"),
        ItemTypeInfo("preprint", "Preprint"),
        ItemTypeInfo("document", "Document"),
    )

    private fun fields(vararg names: String): List<SchemaField> =
        names.map { SchemaField(it, fallbackFieldLabels[it] ?: humaniseSchemaName(it), knownBaseFields[it]) }

    private fun creators(vararg types: String): List<CreatorTypeInfo> =
        types.mapIndexed { index, type -> CreatorTypeInfo(type, fallbackCreatorLabels[type] ?: humaniseSchemaName(type), index == 0) }

    fun schema(type: String): TypeSchema? {
        val info = types.firstOrNull { it.type == type } ?: return null
        val (fieldNames, creatorTypes) = when (type) {
            "journalArticle" -> fields("title", "abstractNote", "publicationTitle", "volume", "issue", "pages", "date",
                "series", "seriesTitle", "seriesText", "journalAbbreviation", "language", "DOI", "ISSN",
                "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber",
                "rights", "extra") to creators("author", "contributor", "editor", "translator", "reviewedAuthor")
            "book" -> fields("title", "abstractNote", "series", "seriesNumber", "volume", "numberOfVolumes",
                "edition", "place", "publisher", "date", "numPages", "language", "ISBN", "shortTitle", "url",
                "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "seriesEditor", "translator")
            "bookSection" -> fields("title", "abstractNote", "bookTitle", "series", "seriesNumber", "volume",
                "edition", "place", "publisher", "date", "pages", "language", "ISBN", "shortTitle", "url",
                "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "bookAuthor", "contributor", "editor", "seriesEditor", "translator")
            "conferencePaper" -> fields("title", "abstractNote", "proceedingsTitle", "conferenceName", "place",
                "publisher", "date", "volume", "pages", "DOI", "ISBN", "ISSN", "shortTitle", "url", "accessDate",
                "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "translator")
            "thesis" -> fields("title", "abstractNote", "thesisType", "university", "place", "date", "numPages",
                "language", "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog",
                "callNumber", "rights", "extra") to creators("author", "contributor")
            "report" -> fields("title", "abstractNote", "reportNumber", "reportType", "seriesTitle", "place",
                "institution", "date", "pages", "shortTitle", "url", "accessDate", "archive", "archiveLocation",
                "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "seriesEditor", "translator")
            "webpage" -> fields("title", "abstractNote", "websiteTitle", "websiteType", "date", "language",
                "shortTitle", "url", "accessDate", "rights", "extra") to
                creators("author", "contributor", "editor", "translator")
            "newspaperArticle" -> fields("title", "abstractNote", "publicationTitle", "place", "date", "section",
                "edition", "pages", "shortTitle", "url", "accessDate", "archive", "archiveLocation",
                "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "translator")
            "magazineArticle" -> fields("title", "abstractNote", "publicationTitle", "volume", "issue", "pages",
                "date", "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog",
                "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "translator")
            "preprint" -> fields("title", "abstractNote", "repository", "archiveID", "date", "DOI", "shortTitle",
                "url", "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights",
                "extra") to creators("author", "contributor")
            "document" -> fields("title", "abstractNote", "publisher", "date", "language", "shortTitle", "url",
                "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra") to
                creators("author", "contributor", "editor", "translator")
            else -> return null
        }
        return TypeSchema(type, info.label, fieldNames, creatorTypes, basic = true)
    }
}

/**
 * A minimal schema for any type when the schema API cannot be reached. Used by the
 * "Use basic form" escape hatch so the user can always continue (title + a few
 * core fields + a single author role). The 11 common types get their fuller
 * classic field set via [ZoteroSchemaFallback.schema]; this covers the rest.
 */
internal fun basicSchema(type: String, label: String): TypeSchema {
    val fields = listOf("title", "abstractNote", "date", "url").map { name ->
        SchemaField(name, fallbackFieldLabels[name] ?: humaniseSchemaName(name), knownBaseFields[name])
    }
    return TypeSchema(type, label, fields,
        listOf(CreatorTypeInfo("author", fallbackCreatorLabels["author"] ?: "Author", primary = true)), basic = true)
}
