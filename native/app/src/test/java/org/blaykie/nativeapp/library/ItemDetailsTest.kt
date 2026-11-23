package org.blaykie.nativeapp.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDetailsTest {
    private fun item(data: String, children: Int? = null) =
        ZoteroItem("K1", Json.parseToJsonElement(data) as JsonObject, children)

    @Test fun journalArticleFieldsInOrderWithRoles() {
        val fields = itemDetailFields(item("""{
            "itemType":"journalArticle","title":"Deep Sea Mining","date":"2021-03-04",
            "creators":[{"creatorType":"author","firstName":"Ada","lastName":"Lovelace"},{"creatorType":"editor","firstName":"Ed","lastName":"Tor"}],
            "publicationTitle":"Ocean Studies","volume":"12","issue":"3","pages":"10-25",
            "DOI":"10.1000/xyz123","url":"https://example.org/paper","ISSN":"1234-5678",
            "abstractNote":"An abstract.","language":"en","tags":[{"tag":"energy"}],
            "collections":["C1"]}""", children = 2), mapOf("C1" to "Thesis"))
        assertEquals(listOf("Type", "Title", "Creators", "Date", "Publication", "Volume", "Issue", "Pages",
            "DOI", "URL", "ISSN", "Abstract", "Language", "Tags", "Collections", "Attachments and notes"),
            fields.map { it.label })
        assertEquals("Journal article", fields[0].value)
        assertEquals("Ada Lovelace (Author)\nEd Tor (Editor)", fields[2].value)
        assertEquals("2", fields.last().value)
    }

    @Test fun bookSkipsMissingFields() {
        val fields = itemDetailFields(item("""{
            "itemType":"book","title":"A Book","date":"2019",
            "creators":[{"creatorType":"author","name":"Solo Name"}],
            "publisher":"Acme Press","place":"Berlin","ISBN":"978-1-4028-9462-6"}"""))
        assertEquals(listOf("Type", "Title", "Creators", "Date", "Publisher", "Place", "ISBN"), fields.map { it.label })
        assertEquals("Solo Name (Author)", fields[2].value)
    }

    @Test fun chapterUsesBookFieldsAndDoiLink() {
        val chapter = item("""{
            "itemType":"bookSection","title":"A Chapter","date":"2020",
            "creators":[{"creatorType":"author","firstName":"Alan","lastName":"Turing"}],
            "bookTitle":"Collected Works","pages":"100-120","publisher":"University Press",
            "DOI":"doi:10.1000/chapter1"}""")
        val fields = itemDetailFields(chapter)
        assertEquals("Book section", fields[0].value)
        assertTrue(fields.none { it.label == "Publication" })
        assertEquals("100-120", fields.first { it.label == "Pages" }.value)
        assertEquals("10.1000/chapter1", doiText(chapter))
        assertEquals("https://doi.org/10.1000/chapter1", doiLink(chapter))
        assertEquals("https://doi.org/10.1000/chapter1", externalLink(chapter))
    }

    @Test fun externalLinkRequiresHttpAndFallsBackToUrl() {
        val web = item("""{"itemType":"webpage","title":"Page","url":"https://example.org"}""")
        assertEquals("https://example.org", externalLink(web))
        val local = item("""{"itemType":"webpage","title":"Page","url":"ftp://example.org/file"}""")
        assertNull(externalLink(local))
        assertNull(doiText(local))
    }
}
