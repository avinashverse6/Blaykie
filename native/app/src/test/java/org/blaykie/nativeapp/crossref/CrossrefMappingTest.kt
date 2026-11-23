package org.blaykie.nativeapp.crossref

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CrossrefMappingTest {
    private fun work(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun doiNormalisationAcceptsThreeForms() {
        assertEquals("10.1000/xyz123", canonicalDoi("10.1000/xyz123"))
        assertEquals("10.1000/xyz123", canonicalDoi("doi:10.1000/XYZ123"))
        assertEquals("10.1000/xyz123", canonicalDoi("https://doi.org/10.1000/XYZ123"))
        assertEquals("10.1000/xyz123", canonicalDoi("  https://doi.org/10.1000%2FXYZ123  ".replace("%2F", "/")))
        assertEquals("10.1234/abc", canonicalDoi("https://doi.org/10.1234%2FABC"))
        assertNull(canonicalDoi("not a doi"))
        assertNull(canonicalDoi("https://example.org/paper"))
        assertNull(canonicalDoi(""))
    }

    @Test fun journalArticleWithThreeAuthorsAndEditorMaps() {
        val draft = crossrefToDraft(work("""{
            "type":"journal-article","title":["A <i>fine</i> paper"],
            "container-title":["Journal of Tests"],"short-container-title":["J. Tests"],
            "volume":"12","issue":"3","page":"10–25",
            "issued":{"date-parts":[[2021,3,4]]},"DOI":"10.1000/XYZ123","URL":"https://doi.org/10.1000/xyz123",
            "ISSN":["1234-5678","9876-5432"],"publisher":"Acme","publisher-location":"Berlin","language":"en",
            "author":[{"given":"Ada","family":"Lovelace"},{"given":"Alan","family":"Turing"},{"given":"Grace","family":"Hopper"}],
            "editor":[{"given":"Ed","family":"Tor"}]}"""))
        assertEquals("journalArticle", draft.itemType)
        assertFalse(draft.typeGuessed)
        assertEquals("A fine paper", draft.fields["title"])
        assertEquals("Journal of Tests", draft.fields["publicationTitle"])
        assertEquals("J. Tests", draft.fields["journalAbbreviation"])
        assertEquals("12", draft.fields["volume"]); assertEquals("3", draft.fields["issue"])
        assertEquals("10–25", draft.fields["pages"])
        assertEquals("2021-03-04", draft.fields["date"])
        assertEquals("10.1000/xyz123", draft.fields["DOI"])
        assertEquals("1234-5678", draft.fields["ISSN"])
        assertEquals("Acme", draft.fields["publisher"]); assertEquals("Berlin", draft.fields["place"])
        assertEquals(listOf("Ada Lovelace" to "author", "Alan Turing" to "author", "Grace Hopper" to "author", "Ed Tor" to "editor"),
            draft.creators.map { it.displayName() to it.role })
    }

    @Test fun bookWithIsbnMaps() {
        val draft = crossrefToDraft(work("""{
            "type":"monograph","title":["The Book"],"issued":{"date-parts":[[2019]]},
            "ISBN":["978-1-4028-9462-6","978-0-00-000000-0"],"publisher":"Acme Press",
            "author":[{"name":"Solo Name"}]}"""))
        assertEquals("book", draft.itemType)
        assertEquals("978-1-4028-9462-6", draft.fields["ISBN"])
        assertEquals("2019", draft.fields["date"])
        assertEquals("", draft.creators.single().firstName)
        assertEquals("Solo Name", draft.creators.single().name)
        val json = Json.parseToJsonElement(buildItemJson(draft)) as JsonArray
        val creator = (json[0].jsonObject["creators"] as JsonArray)[0].jsonObject
        assertEquals("\"Solo Name\"", creator["name"].toString())
    }

    @Test fun chapterMapsToBookSectionWithBookTitle() {
        val draft = crossrefToDraft(work("""{
            "type":"book-chapter","title":["A Chapter"],"container-title":["Collected Works"],
            "page":"100-120","issued":{"date-parts":[[2020,5]]},
            "author":[{"given":"Alan","family":"Turing"}],"ISBN":["978-0-12-345678-9"]}"""))
        assertEquals("bookSection", draft.itemType)
        assertEquals("Collected Works", draft.fields["bookTitle"])
        assertNull(draft.fields["publicationTitle"])
        assertEquals("978-0-12-345678-9", draft.fields["ISBN"])
        assertEquals("2020-05", draft.fields["date"])
    }

    @Test fun preprintAndUnknownType() {
        val preprint = crossrefToDraft(work("""{"type":"posted-content","title":["Early Results"],
            "issued":{"date-parts":[[2024]]},"author":[{"given":"A","family":"B"}]}"""))
        assertEquals("preprint", preprint.itemType)
        val unknown = crossrefToDraft(work("""{"type":"dataset","title":["Data"],"issued":{"date-parts":[[2024]]}}"""))
        assertEquals("journalArticle", unknown.itemType)
        assertTrue(unknown.typeGuessed)
    }

    @Test fun jatsAndHtmlAreStripped() {
        assertEquals("An abstract with bold text.", stripMarkup("<jats:p>An <jats:bold>abstract</jats:bold> with <i>bold</i> text.</jats:p>"))
        val draft = crossrefToDraft(work("""{"type":"journal-article",
            "title":["Fish &amp; Chips <jats:italic>revisited</jats:italic>"],
            "abstract":"<jats:p>Summary &amp; findings.</jats:p>","issued":{"date-parts":[[2022]]}}"""))
        assertEquals("Fish & Chips revisited", draft.fields["title"])
        assertEquals("Summary & findings.", draft.fields["abstractNote"])
    }

    @Test fun builtJsonIsOneItemWithCollectionsWhenChosen() {
        val draft = crossrefToDraft(work("""{"type":"journal-article","title":["T"],"DOI":"10.1/a",
            "issued":{"date-parts":[[2000]]},"author":[{"given":"A","family":"B"}]}"""))
        val array = Json.parseToJsonElement(buildItemJson(draft, "COLL1234")) as JsonArray
        assertEquals(1, array.size)
        val item = array[0].jsonObject
        assertEquals("\"journalArticle\"", item["itemType"].toString())
        assertEquals("\"COLL1234\"", (item["collections"] as JsonArray)[0].toString())
        val plain = Json.parseToJsonElement(buildItemJson(draft)) as JsonArray
        assertNull((plain[0].jsonObject)["collections"])
    }
}
