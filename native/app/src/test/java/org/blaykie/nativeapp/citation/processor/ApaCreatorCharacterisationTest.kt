package org.blaykie.nativeapp.citation.processor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERISATION ONLY: records how the built-in APA 7 formatter and the Insert
 * citation preview render creator cases TODAY. These tests must never be used to
 * change formatter output; if a case is fixed later, update the recorded values
 * deliberately with the fix. The printed lines are the source for the report table.
 */
class ApaCreatorCharacterisationTest {
    private fun item(key: String, dataJson: String): CitationItem {
        val data = Json.parseToJsonElement(dataJson) as JsonObject
        return ZoteroItem(key, data).citationItem()
    }

    private fun authorPart(item: CitationItem, suffix: String = ""): String {
        val reference = CitationFormatting.reference(item, suffix)
            .joinToString("") { it.first }.trim()
        if (item.authors.isEmpty()) return "(no author — reference starts with the title)"
        val yearMark = " (${CitationFormatting.displayYear(item)}"
        val index = reference.indexOf(yearMark)
        return if (index >= 0) reference.substring(0, index) else reference
    }

    private fun report(id: String, item: CitationItem, context: List<CitationItem> = listOf(item)) {
        val inText = runBlocking { BuiltInApaProcessor.citation(listOf(item), "parenthetical", "", "", context) }
        val narrative = runBlocking { BuiltInApaProcessor.citation(listOf(item), "narrative", "", "", context) }
        val suffix = CitationFormatting.suffixes(context)[item.key].orEmpty()
        println("APA-CASE|$id|intext=$inText|narrative=$narrative|refauthors=${authorPart(item, suffix)}")
    }

    private fun journal(key: String, creators: String, title: String = "A study of things",
                        date: String = "2021"): CitationItem = item(key, """{
        "itemType":"journalArticle","title":"$title","date":"$date",
        "publicationTitle":"Journal of Things","volume":"12","issue":"3","pages":"1–10",
        "creators":$creators}""")

    @Test fun recordCreatorCases() {
        // (a) one person
        report("a", journal("a", """[{"creatorType":"author","firstName":"R. K.","lastName":"Sharma"}]"""))
        // (b) two persons
        report("b", journal("b", """[
            {"creatorType":"author","firstName":"R. K.","lastName":"Sharma"},
            {"creatorType":"author","firstName":"Meera","lastName":"Patel"}]"""))
        // (c) three persons
        report("c", journal("c", """[
            {"creatorType":"author","firstName":"R. K.","lastName":"Sharma"},
            {"creatorType":"author","firstName":"Meera","lastName":"Patel"},
            {"creatorType":"author","firstName":"John","lastName":"Smith"}]"""))
        // (d) six persons
        report("d", journal("d", (1..6).joinToString(",", "[", "]") {
            """{"creatorType":"author","firstName":"First$it","lastName":"Author$it"}""" }))
        // (e) 21 persons
        report("e", journal("e", (1..21).joinToString(",", "[", "]") {
            """{"creatorType":"author","firstName":"A","lastName":"Author$it"}""" }))
        // (f) an organisation (single-field creator)
        report("f", journal("f", """[{"creatorType":"author","name":"Ministry of Rural Development"}]"""))
        // (g) a single-name person (also a single-field creator in Zotero)
        report("g", journal("g", """[{"creatorType":"author","name":"Kabir"}]"""))
        // (h) a person with a particle
        report("h", journal("h", """[{"creatorType":"author","firstName":"Jan","lastName":"van der Berg"}]"""))
        // (i) an author with a suffix, typed into First name after a comma (Zotero convention)
        report("i", journal("i", """[{"creatorType":"author","firstName":"John, Jr.","lastName":"Smith"}]"""))
        // (j) an editor-only book
        report("j", item("j", """{"itemType":"book","title":"The edited volume","date":"2020",
            "publisher":"Academic Press",
            "creators":[{"creatorType":"editor","firstName":"Priya","lastName":"Nair"}]}"""))
        // (k) no author
        report("k", journal("k", "[]"))
        // (l) an organisation that is also the publisher
        report("l", item("l", """{"itemType":"book","title":"Global health report","date":"2022",
            "publisher":"World Health Organization",
            "creators":[{"creatorType":"author","name":"World Health Organization"}]}"""))
        // (m) two works by the same first author in the same year
        val first = journal("m1", """[{"creatorType":"author","firstName":"R. K.","lastName":"Sharma"}]""",
            title = "Alpha findings", date = "2020")
        val second = journal("m2", """[{"creatorType":"author","firstName":"R. K.","lastName":"Sharma"}]""",
            title = "Beta findings", date = "2020")
        val context = listOf(first, second)
        report("m-first", first, context)
        report("m-second", second, context)
        // (n) a Devanagari name
        report("n", journal("n", """[{"creatorType":"author","firstName":"राम","lastName":"शर्मा"}]"""))
        assertTrue(true)
    }
}
