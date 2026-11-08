package org.blaykie.nativeapp.csl.spike

import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.junit.Assert.*
import org.junit.Test

class CslSpikeTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")) { "Missing asset: $name" }.bufferedReader().use { it.readText() }
    @Test fun renderFiveAndCompareExistingApa() {
        val result = CslSpike.run(asset("citeproc.js"), asset("apa.csl"), asset("locales-en-US.xml"), CslSpike.fixtures(5))
        assertTrue(result.parenthetical.isNotBlank())
        assertTrue(result.authorOnly.isNotBlank()); assertTrue(result.suppressAuthor.isNotBlank())
        assertTrue(result.authorOnly.contains("Author0")); assertTrue(result.suppressAuthor.contains("2020"))
        assertEquals(5, result.bibliography.size)
        val types = listOf("journalArticle", "book", "webpage", "report", "journalArticle")
        val items = types.mapIndexed { i, type -> CitationItem(key="item$i", title="Example title ${i+1}",
            authors=listOf(CitationAuthor(if (i == 4) "अनिता" else "Alex", if (i == 4) "शर्मा" else "Author$i")),
            date="${2020+i%4}", itemType=type, publicationTitle=if(type=="journalArticle") "Example Journal" else "",
            volume=if(type=="journalArticle") "2" else "", pages=if(type=="journalArticle") "10-20" else "",
            publisher=if(type=="book" || type=="report") "Example Press" else "", url=if(type=="webpage") "https://example.org/page" else "") }
        println("CSL parenthetical: ${result.parenthetical}")
        println("Kotlin parenthetical: ${CitationFormatting.citationText(items)}")
        println("CSL narrative: ${result.authorOnly} ${result.suppressAuthor}")
        println("Kotlin narrative: ${CitationFormatting.citationText(items.take(1), "narrative")}")
        println("COMPARISON TABLE: item | CSL APA bibliography | Kotlin APA bibliography")
        items.forEachIndexed { i, item ->
            val entry = result.bibliography.single { it.contains("Example title ${i+1}", ignoreCase = true) }
            println("${types[i]} | $entry | ${CitationFormatting.referenceList(listOf(item))}")
        }
        println("CSL bibliography:\n${result.bibliography.joinToString("\n")}")
        println("Kotlin bibliography:\n${CitationFormatting.referenceList(items)}")
    }
}
