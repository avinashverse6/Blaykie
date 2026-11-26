package org.blaykie.nativeapp.library

import org.blaykie.nativeapp.zotero.SchemaField
import org.blaykie.nativeapp.zotero.TypeSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFieldGroupingTest {
    private fun group(type: String, vararg fields: String): OrderedFormFields =
        orderedFormFields(TypeSchema(type, type, fields.map { SchemaField(it, it) }, emptyList()))

    private fun List<SchemaField>.names() = map { it.name }

    @Test fun journalBookAndWebPageGroups() {
        val journal = group("journalArticle", "title", "abstractNote", "publicationTitle", "volume", "issue",
            "pages", "date", "series", "seriesTitle", "seriesText", "journalAbbreviation", "language", "DOI",
            "ISSN", "shortTitle", "url", "accessDate", "archive", "archiveLocation", "libraryCatalog",
            "callNumber", "rights", "extra", "publisher")
        assertEquals(listOf("title", "publicationTitle", "volume", "issue", "pages", "date", "DOI", "url", "language", "abstractNote"), journal.main.names())
        assertEquals(setOf("series", "seriesTitle", "seriesText", "journalAbbreviation", "ISSN", "shortTitle",
            "accessDate", "archive", "archiveLocation", "libraryCatalog", "callNumber", "rights", "extra"), journal.optional.names().toSet())
        assertEquals(listOf("publisher"), journal.more.names())

        val book = group("book", "title", "abstractNote", "series", "edition", "place", "publisher", "volume",
            "date", "numPages", "language", "ISBN", "url", "accessDate", "callNumber", "extra")
        assertEquals(listOf("title", "publisher", "place", "volume", "numPages", "date", "ISBN", "url", "language", "abstractNote"), book.main.names())
        assertEquals(setOf("series", "edition", "accessDate", "callNumber", "extra"), book.optional.names().toSet())
        assertTrue(book.more.isEmpty())

        val web = group("webpage", "title", "abstractNote", "websiteTitle", "date", "language", "shortTitle",
            "url", "accessDate", "rights", "extra", "DOI")
        assertEquals(listOf("title", "websiteTitle", "date", "url", "accessDate", "language", "abstractNote"), web.main.names())
        assertEquals(setOf("shortTitle", "rights", "extra"), web.optional.names().toSet())
        assertEquals(listOf("DOI"), web.more.names())
    }

    @Test fun languageAndWebAccessAreVisibleAcrossOtherTypes() {
        val thesis = group("thesis", "title", "university", "date", "language", "accessDate")
        assertTrue("language" in thesis.main.names())
        assertTrue("accessDate" in thesis.optional.names())
        assertTrue("university" in thesis.main.names())
        for (type in listOf("blogPost", "forumPost")) {
            val fields = group(type, "title", "url", "accessDate", "language", "extra")
            assertTrue("accessDate" in fields.main.names())
            assertTrue("language" in fields.main.names())
            assertTrue("extra" in fields.optional.names())
        }
    }
}
