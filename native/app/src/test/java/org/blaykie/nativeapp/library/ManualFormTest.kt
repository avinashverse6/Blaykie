package org.blaykie.nativeapp.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.crossref.buildItemJson
import org.blaykie.nativeapp.zotero.CreatorTypeInfo
import org.blaykie.nativeapp.zotero.SchemaField
import org.blaykie.nativeapp.zotero.TypeSchema
import org.junit.Assert.*
import org.junit.Test

class ManualFormTest {
    private fun schema(type: String, fields: List<String>, creators: List<String>, label: String = type): TypeSchema =
        TypeSchema(type, label, fields.map { name ->
            SchemaField(name, name, if (name == "bookTitle") "publicationTitle" else null)
        }, creators.mapIndexed { index, role -> CreatorTypeInfo(role, role, index == 0) })

    private val journal = schema("journalArticle",
        listOf("title", "abstractNote", "publicationTitle", "volume", "issue", "pages", "date", "journalAbbreviation",
            "language", "DOI", "ISSN", "shortTitle", "url", "accessDate", "publisher", "place"),
        listOf("author", "contributor", "editor", "translator", "reviewedAuthor"))
    private val bookSection = schema("bookSection",
        listOf("title", "abstractNote", "bookTitle", "publisher", "place", "pages", "date", "edition", "ISBN", "url"),
        listOf("author", "bookAuthor", "contributor", "editor", "seriesEditor", "translator"))
    private val webpage = schema("webpage",
        listOf("title", "abstractNote", "websiteTitle", "websiteType", "date", "url", "accessDate", "DOI"),
        listOf("author", "contributor", "editor", "translator"))
    private val thesis = schema("thesis",
        listOf("title", "abstractNote", "thesisType", "university", "place", "date", "url"),
        listOf("author", "contributor"))
    private val presentation = schema("presentation",
        listOf("title", "abstractNote", "presentationType", "meetingName", "place", "date", "url", "accessDate"),
        listOf("presenter", "contributor"))
    private val interview = schema("interview",
        listOf("title", "abstractNote", "interviewMedium", "date", "url", "accessDate"),
        listOf("interviewee", "interviewer", "contributor"))

    private fun savedObject(draftJson: String): JsonObject =
        (Json.parseToJsonElement(draftJson) as JsonArray).first() as JsonObject

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.creators(): List<JsonObject> =
        (this["creators"] as JsonArray).map { it as JsonObject }

    @Test fun postBodyUsesTemplateFieldNames() {
        val journalDraft = buildManualDraft(journal,
            mapOf("title" to "Paper", "publicationTitle" to "Journal of Things", "volume" to "12", "DOI" to "10.1/abc"),
            listOf(ManualCreatorRow(role = "author", lastName = "Sharma", firstName = "R. K.")))
        val journalObj = savedObject(buildItemJson(journalDraft))
        assertEquals("journalArticle", journalObj.text("itemType"))
        assertEquals("Journal of Things", journalObj.text("publicationTitle"))
        assertEquals("10.1/abc", journalObj.text("DOI"))

        val chapterObj = savedObject(buildItemJson(buildManualDraft(bookSection,
            mapOf("title" to "Chapter", "bookTitle" to "The Big Book", "ISBN" to "978-1-4028-9462-6"),
            listOf(
                ManualCreatorRow(role = "author", lastName = "Sharma", firstName = "R. K."),
                ManualCreatorRow(role = "editor", lastName = "Nair", firstName = "Priya")))))
        assertEquals("The Big Book", chapterObj.text("bookTitle"))
        assertEquals("978-1-4028-9462-6", chapterObj.text("ISBN"))
        assertFalse(chapterObj.containsKey("publicationTitle"))
        assertEquals(listOf("author", "editor"), chapterObj.creators().map { it.text("creatorType") })

        val thesisObj = savedObject(buildItemJson(buildManualDraft(thesis,
            mapOf("title" to "Thesis", "thesisType" to "PhD thesis", "university" to "IIT Bombay"), emptyList())))
        assertEquals("IIT Bombay", thesisObj.text("university"))
        assertEquals("PhD thesis", thesisObj.text("thesisType"))

        val presentationObj = savedObject(buildItemJson(buildManualDraft(presentation,
            mapOf("title" to "Talk", "presentationType" to "Paper", "meetingName" to "Conf 2026"),
            listOf(ManualCreatorRow(role = "presenter", lastName = "Sharma")))))
        assertEquals("Conf 2026", presentationObj.text("meetingName"))
        assertEquals("presenter", presentationObj.creators().first().text("creatorType"))

        val interviewObj = savedObject(buildItemJson(buildManualDraft(interview,
            mapOf("title" to "A conversation", "interviewMedium" to "Audio"),
            listOf(ManualCreatorRow(role = "interviewee", lastName = "Kabir", singleField = false)))))
        assertEquals("Audio", interviewObj.text("interviewMedium"))
        assertEquals("interviewee", interviewObj.creators().first().text("creatorType"))
    }

    @Test fun webpageGetsTodaysAccessDateByDefault() {
        assertEquals(mapOf("accessDate" to "2026-11-19"), defaultFieldValues(webpage, "2026-11-19"))
        assertTrue(defaultFieldValues(journal, "2026-11-19").isEmpty())
        val draft = buildManualDraft(webpage,
            mapOf("title" to "Page", "websiteTitle" to "Example Site") + defaultFieldValues(webpage, "2026-11-19"),
            emptyList())
        assertEquals("2026-11-19", savedObject(buildItemJson(draft)).text("accessDate"))
    }

    @Test fun classicFieldsStayOnTopAndNewerFieldsGoToMoreFields() {
        val ordered = orderedFormFields(journal)
        assertTrue(ordered.main.any { it.name == "DOI" })
        assertTrue(ordered.main.any { it.name == "title" })
        // publisher and place on a journal article were added by newer Zotero.
        assertEquals(listOf("place", "publisher"), ordered.more.map { it.name }.sorted())
        assertEquals("title", ordered.main.first().name)
        assertEquals("abstractNote", ordered.main.last().name)
        // A DOI on a web page is not classic either.
        assertEquals(listOf("DOI"), orderedFormFields(webpage).more.map { it.name })
    }

    @Test fun creatorMappingPersonOrganisationAndSingleName() {
        val draft = buildManualDraft(journal, mapOf("title" to "T"), listOf(
            ManualCreatorRow(role = "author", lastName = "Sharma", firstName = "R. K."),
            ManualCreatorRow(role = "author", singleField = true, name = "Ministry of Rural Development"),
            ManualCreatorRow(role = "author", singleField = true, name = "Kabir"),
            ManualCreatorRow(role = "author")))
        val creators = savedObject(buildItemJson(draft)).creators()
        assertEquals(3, creators.size) // the empty row is dropped
        assertEquals("Sharma", creators[0].text("lastName"))
        assertEquals("R. K.", creators[0].text("firstName"))
        assertNull(creators[0].text("name"))
        assertEquals("Ministry of Rural Development", creators[1].text("name"))
        assertNull(creators[1].text("lastName"))
        assertEquals("Kabir", creators[2].text("name"))
    }

    @Test fun creatorOrderIsKeptExactly() {
        val rows = (1..6).map { ManualCreatorRow(role = "author", lastName = "Author$it", firstName = "F$it") }
        val creators = savedObject(buildItemJson(buildManualDraft(journal, mapOf("title" to "T"), rows))).creators()
        assertEquals((1..6).map { "Author$it" }, creators.map { it.text("lastName") })
    }

    @Test fun roleOutsideTheTypesCreatorTypesFallsBackToPrimary() {
        val draft = buildManualDraft(journal, mapOf("title" to "T"),
            listOf(ManualCreatorRow(role = "director", lastName = "Sharma")))
        assertEquals("author", draft.creators.first().role)
    }

    @Test fun pasteSeveralAuthorsParser() {
        val rows = parsePastedCreators(
            "Sharma, R. K.\nPatel, Meera; Ministry of Rural Development\nSmith and Wesson\nKabir")
        // Split only on new lines and semicolons: Sharma, Patel, Ministry, Smith and Wesson, Kabir.
        assertEquals(5, rows.size)
        assertFalse(rows[0].singleField)
        assertEquals("Sharma", rows[0].lastName); assertEquals("R. K.", rows[0].firstName)
        assertFalse(rows[1].singleField)
        assertEquals("Patel", rows[1].lastName); assertEquals("Meera", rows[1].firstName)
        // Never split on "and" or "&": organisations keep their full name.
        assertTrue(rows[2].singleField); assertEquals("Ministry of Rural Development", rows[2].name)
        assertTrue(rows[3].singleField); assertEquals("Smith and Wesson", rows[3].name)
        assertTrue(rows[4].singleField); assertEquals("Kabir", rows[4].name)
        // Order is kept and empty parts are skipped.
        assertTrue(parsePastedCreators("\n ; \n").isEmpty())
        val suffix = parsePastedCreators("King, Martin Luther, Jr.")
        assertEquals("King", suffix.single().lastName)
        assertEquals("Martin Luther, Jr.", suffix.single().firstName)
    }

    @Test fun commaSuggestions() {
        assertEquals("Sharma" to "R. K.", splitLastFirst("Sharma, R. K."))
        assertNull(splitLastFirst("Sharma"))
        assertTrue(looksLikeLastFirst("Sharma, R. K."))
        assertFalse(looksLikeLastFirst("Ministry of Rural Development"))
        assertFalse(looksLikeLastFirst("Sharma,"))
        // Particles stay in the last name, unsplit and uncapitalised.
        val particle = parsePastedCreators("van der Berg, Jan")
        assertEquals("van der Berg", particle.single().lastName)
        assertEquals("Jan", particle.single().firstName)
    }

    @Test fun nonLatinNamesArePreservedByteForByte() {
        val last = "शर्मा"
        val first = "राम कुमार"
        assertEquals(last, cleanCreatorName("  $last  "))
        assertEquals("राम कुमार", cleanCreatorName("राम   कुमार"))
        val draft = buildManualDraft(journal, mapOf("title" to "T"),
            listOf(ManualCreatorRow(role = "author", lastName = last, firstName = first)))
        val creator = savedObject(buildItemJson(draft)).creators().single()
        assertEquals(last, creator.text("lastName"))
        assertArrayEquals(last.toByteArray(Charsets.UTF_8),
            creator.text("lastName")!!.toByteArray(Charsets.UTF_8))
    }

    @Test fun emptyOptionalFieldsAreLeftOutOfTheJson() {
        val draft = buildManualDraft(journal,
            mapOf("title" to "Paper", "volume" to "", "issue" to "   ", "pages" to "1–10"), emptyList())
        val obj = savedObject(buildItemJson(draft))
        assertFalse(obj.containsKey("volume"))
        assertFalse(obj.containsKey("issue"))
        assertEquals("1–10", obj.text("pages"))
        assertEquals(0, (obj["creators"] as JsonArray).size)
    }

    @Test fun titleOrItsBaseMappedFieldIsRequired() {
        assertFalse(titleSatisfied(journal, mapOf("volume" to "3")))
        assertTrue(titleSatisfied(journal, mapOf("title" to "Paper")))
        val caseSchema = TypeSchema("case", "Case",
            listOf(SchemaField("caseName", "Case Name", "title"), SchemaField("court", "Court")),
            listOf(CreatorTypeInfo("author", "Author", true)))
        assertEquals("caseName", titleFieldOf(caseSchema)?.name)
        assertFalse(titleSatisfied(caseSchema, emptyMap()))
        assertTrue(titleSatisfied(caseSchema, mapOf("caseName" to "State v. Example")))
        // The base "title" key is never sent next to its type-specific name.
        val draft = buildManualDraft(caseSchema, mapOf("caseName" to "State v. Example", "title" to "Other"),
            emptyList())
        assertEquals("State v. Example", savedObject(buildItemJson(draft)).text("caseName"))
        val statuteSchema = TypeSchema("statute", "Statute",
            listOf(SchemaField("nameOfAct", "Name of Act")), listOf(CreatorTypeInfo("author", "Author", true)))
        assertEquals("nameOfAct", titleFieldOf(statuteSchema)?.name)
    }

    @Test fun discardLogic() {
        val fresh = listOf(ManualCreatorRow(role = "author"))
        assertFalse(manualFormDirty(journal, emptyMap(), fresh, "2026-11-19"))
        assertTrue(manualFormDirty(journal, mapOf("title" to "Typed"), fresh, "2026-11-19"))
        assertTrue(manualFormDirty(journal, emptyMap(),
            listOf(ManualCreatorRow(role = "author", lastName = "Sharma")), "2026-11-19"))
        assertTrue(manualFormDirty(journal, emptyMap(), emptyList(), "2026-11-19"))
        // Today's access date alone does not make a fresh web-page form dirty.
        assertFalse(manualFormDirty(webpage, defaultFieldValues(webpage, "2026-11-19"),
            listOf(ManualCreatorRow(role = "author")), "2026-11-19"))
        // Deleting typed text again makes the form clean.
        assertFalse(manualFormDirty(journal, mapOf("title" to "  "), fresh, "2026-11-19"))
    }

    @Test fun dateIsStoredExactlyAsTyped() {
        val draft = buildManualDraft(journal, mapOf("title" to "T", "date" to "May 2021"), emptyList())
        assertEquals("May 2021", savedObject(buildItemJson(draft)).text("date"))
        val noDate = buildManualDraft(journal, mapOf("title" to "T", "date" to "n.d."), emptyList())
        assertEquals("n.d.", savedObject(buildItemJson(noDate)).text("date"))
    }
}
