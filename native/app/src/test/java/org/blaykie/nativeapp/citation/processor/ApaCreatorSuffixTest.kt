package org.blaykie.nativeapp.citation.processor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APA 7 creator suffixes and editor markers. A suffix is typed into the Zotero first-name
 * field ("John, Jr." or "John Jr"); it must be printed after the initials in the reference
 * list and must never appear in the in-text citation.
 */
class ApaCreatorSuffixTest {
    private fun item(key: String, dataJson: String): CitationItem {
        val data = Json.parseToJsonElement(dataJson) as JsonObject
        return ZoteroItem(key, data).citationItem()
    }

    private fun journal(key: String, creators: String): CitationItem = item(key, """{
        "itemType":"journalArticle","title":"A study of things","date":"2021",
        "publicationTitle":"Journal of Things","volume":"12","issue":"3","pages":"1-10",
        "creators":$creators}""")

    private fun reference(item: CitationItem): String =
        CitationFormatting.reference(item).joinToString("") { it.first }.trim()

    /** The author block of a reference, i.e. everything before the " (year)" date. */
    private fun authorBlock(item: CitationItem): String {
        val text = reference(item)
        val mark = " (${CitationFormatting.displayYear(item)}"
        val index = text.indexOf(mark)
        return if (index >= 0) text.substring(0, index) else text
    }

    private fun inText(item: CitationItem): String =
        runBlocking { BuiltInApaProcessor.citation(listOf(item), "parenthetical") }

    @Test fun suffixJrWithCommaIsPrintedAfterTheInitials() {
        val item = journal("a", """[{"creatorType":"author","firstName":"John, Jr.","lastName":"Smith"}]""")
        assertEquals("Smith, J., Jr.", authorBlock(item))
        assertEquals("(Smith, 2021)", inText(item))
        assertEquals("John" to "Jr.", CitationFormatting.splitCreatorSuffix("John, Jr."))
    }

    @Test fun suffixSrWithCommaIsPrintedAfterTheInitials() {
        val item = journal("b", """[{"creatorType":"author","firstName":"John, Sr.","lastName":"Smith"}]""")
        assertEquals("Smith, J., Sr.", authorBlock(item))
        assertEquals("(Smith, 2021)", inText(item))
    }

    @Test fun romanNumeralSuffixHasNoPeriod() {
        val item = journal("c", """[{"creatorType":"author","firstName":"John, III","lastName":"Smith"}]""")
        assertEquals("Smith, J., III", authorBlock(item))
        assertEquals("(Smith, 2021)", inText(item))
        assertEquals("IV", CitationFormatting.splitCreatorSuffix("Mary IV").second)
        assertEquals("V", CitationFormatting.splitCreatorSuffix("Mary, V").second)
    }

    @Test fun suffixWithoutCommaIsRecognised() {
        val item = journal("d", """[{"creatorType":"author","firstName":"John Jr","lastName":"Smith"}]""")
        assertEquals("Smith, J., Jr.", authorBlock(item))
        assertEquals("(Smith, 2021)", inText(item))
    }

    @Test fun ordinaryNamesAreNotSuffixes() {
        // "Jay" is a given name, not a generational suffix; a lone "Jr" is the whole first name.
        val jay = journal("e", """[{"creatorType":"author","firstName":"Jay","lastName":"Smith"}]""")
        assertEquals("Smith, J.", authorBlock(jay))
        assertNull(CitationFormatting.splitCreatorSuffix("Jay").second)
        assertEquals("Jay" to null, CitationFormatting.splitCreatorSuffix("Jay"))
        assertEquals("Jr" to null, CitationFormatting.splitCreatorSuffix("Jr"))
        val twoNames = journal("f", """[{"creatorType":"author","firstName":"John Jay","lastName":"Smith"}]""")
        assertEquals("Smith, J. J.", authorBlock(twoNames))
    }

    @Test fun severalAuthorsWithOneSuffix() {
        val item = journal("g", """[
            {"creatorType":"author","firstName":"Meera","lastName":"Patel"},
            {"creatorType":"author","firstName":"John, Jr.","lastName":"Smith"}]""")
        assertEquals("Patel, M., & Smith, J., Jr.", authorBlock(item))
        assertEquals("(Patel & Smith, 2021)", inText(item))
    }

    @Test fun editorOnlyBookHasPeriodAfterTheMarkerAndTheYear() {
        val single = item("h", """{"itemType":"book","title":"The edited volume","date":"2020",
            "publisher":"Academic Press",
            "creators":[{"creatorType":"editor","firstName":"Priya","lastName":"Nair"}]}""")
        assertEquals("Nair, P. (Ed.).", authorBlock(single))
        assertEquals("Nair, P. (Ed.). (2020). The edited volume. Academic Press.", reference(single))
        val several = item("i", """{"itemType":"book","title":"The edited volume","date":"2020",
            "publisher":"Academic Press",
            "creators":[{"creatorType":"editor","firstName":"Priya","lastName":"Nair"},
                {"creatorType":"editor","firstName":"Sunita","lastName":"Rao"}]}""")
        assertEquals("Nair, P., & Rao, S. (Eds.).", authorBlock(several))
        assertEquals("Nair, P., & Rao, S. (Eds.). (2020). The edited volume. Academic Press.", reference(several))
    }

    @Test fun editorWithASuffixKeepsTheSuffix() {
        val item = item("j", """{"itemType":"book","title":"The edited volume","date":"2020",
            "publisher":"Academic Press",
            "creators":[{"creatorType":"editor","firstName":"Priya, Jr.","lastName":"Nair"}]}""")
        assertEquals("Nair, P., Jr. (Ed.).", authorBlock(item))
    }

    @Test fun chapterEditorsKeepTheirRoleAndAnySuffix() {
        val chapter = item("k", """{"itemType":"bookSection","title":"A chapter","date":"2020",
            "bookTitle":"The book","publisher":"Press","pages":"1-10",
            "creators":[{"creatorType":"author","firstName":"Ann","lastName":"Author"},
                {"creatorType":"editor","firstName":"Priya","lastName":"Nair"}]}""")
        assertEquals("Author, A. (2020). A chapter. In P. Nair (Ed.), The book (pp. 1–10). Press.",
            reference(chapter))
        val withSuffix = item("l", """{"itemType":"bookSection","title":"A chapter","date":"2020",
            "bookTitle":"The book","publisher":"Press","pages":"1-10",
            "creators":[{"creatorType":"author","firstName":"Ann","lastName":"Author"},
                {"creatorType":"editor","firstName":"Priya, Jr.","lastName":"Nair"}]}""")
        assertEquals("Author, A. (2020). A chapter. In P. Nair, Jr. (Ed.), The book (pp. 1–10). Press.",
            reference(withSuffix))
    }

    @Test fun chapterEditorsWithAnOrganisationAuthorUseTheSameForm() {
        val chapter = item("m", """{"itemType":"bookSection","title":"A chapter","date":"2020",
            "bookTitle":"The book","publisher":"Press","pages":"1-10",
            "creators":[{"creatorType":"author","name":"World Health Organization"},
                {"creatorType":"editor","firstName":"Priya, Jr.","lastName":"Nair"}]}""")
        assertEquals("World Health Organization. (2020). A chapter. In P. Nair, Jr. (Ed.), The book (pp. 1–10). Press.",
            reference(chapter))
    }

    @Test fun nonLatinNamesAreUnaffected() {
        assertEquals("राम" to null, CitationFormatting.splitCreatorSuffix("राम"))
        assertEquals("शर्मा" to null, CitationFormatting.splitCreatorSuffix("शर्मा"))
        val devanagari = journal("n", """[{"creatorType":"author","firstName":"राम","lastName":"शर्मा"}]""")
        assertEquals("(शर्मा, 2021)", inText(devanagari))
    }
}
