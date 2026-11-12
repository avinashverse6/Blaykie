package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.junit.Assert.*
import org.junit.Test

class CitationItemToCslTest {
    @Test fun mapsTypes() {
        val types = mapOf("journalArticle" to "article-journal", "book" to "book", "bookSection" to "chapter", "magazineArticle" to "article-magazine", "newspaperArticle" to "article-newspaper", "webpage" to "webpage", "blogPost" to "post-weblog", "report" to "report", "thesis" to "thesis", "conferencePaper" to "paper-conference", "preprint" to "article")
        types.forEach { (source, target) -> assertEquals(target, CitationItemToCsl.convert(CitationItem("x", "Title", emptyList(), "", itemType=source))["type"]!!.jsonPrimitive.content) }
    }
    @Test fun preservesNamesDatesAndMissingFields() {
        val authors = listOf(CitationAuthor("अनिता", "शर्मा"), CitationAuthor("હેના", "પટેલ"), CitationAuthor("", "Research Group", true))
        val csl = CitationItemToCsl.convert(CitationItem("x", "Title", authors, "2024-04-15"))
        val names = csl["author"]!!.jsonArray
        assertEquals("अनिता", names[0].jsonObject["given"]!!.jsonPrimitive.content)
        assertEquals("शर्मा", names[0].jsonObject["family"]!!.jsonPrimitive.content)
        assertEquals("હેના", names[1].jsonObject["given"]!!.jsonPrimitive.content)
        assertEquals("પટેલ", names[1].jsonObject["family"]!!.jsonPrimitive.content)
        assertEquals("Research Group", names[2].jsonObject["literal"]!!.jsonPrimitive.content)
        assertEquals("[[2024,4,15]]", csl["issued"]!!.jsonObject["date-parts"].toString())
        val noData = CitationItemToCsl.convert(CitationItem("y", "Title", emptyList(), "n.d."))
        assertFalse(noData.containsKey("author")); assertFalse(noData.containsKey("issued"))
    }
    @Test fun selectsCitationIdsFromContext() {
        val a = CitationItem("a", "A", emptyList(), "2020")
        val b = CitationItem("b", "B", emptyList(), "2021")
        val request = CitationItemToCsl.request(listOf(b), listOf(a,b))
        assertEquals(listOf("b"), request.citationIds)
        assertEquals(2, request.records.size)
        request.toJson()
    }
}
