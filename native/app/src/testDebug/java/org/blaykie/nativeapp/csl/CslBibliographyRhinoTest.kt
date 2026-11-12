package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.citation.processor.BuiltInApaProcessor
import org.blaykie.nativeapp.citation.processor.CitationCluster
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import kotlinx.coroutines.runBlocking

/** Exercises citeproc's document-wide API directly; not yet connected to the WebView host. */
class CslBibliographyRhinoTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")).bufferedReader().use { it.readText() }
    @Test fun richEntriesSortAndSameYearCitationsDisambiguate() = runBlocking {
        val author = listOf(CitationAuthor("Alex", "Smith"))
        val items = listOf(
            CitationItem("a", "Alpha", author, "2020", itemType="journalArticle", publicationTitle="Example Journal", volume="3"),
            CitationItem("b", "Beta", author, "2020", itemType="book", publisher="Example Press"),
            CitationItem("c", "A webpage", listOf(CitationAuthor("Jo", "Young")), "2021", itemType="webpage", url="https://example.org/"),
            CitationItem("d", "Hindi example", listOf(CitationAuthor("अनिता", "शर्मा")), "2022", itemType="journalArticle", publicationTitle="Hindi Journal"),
        )
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "spikeStyle", asset("apa.csl"))
            ScriptableObject.putProperty(scope, "spikeLocale", asset("locales-en-US.xml"))
            val clusters = items.mapIndexed { i, item -> CitationCluster("c$i", listOf(item.key), mode = if (i == 1) "narrative" else "parenthetical") }
            cx.evaluateString(scope, asset("citeproc.js"), "citeproc.js", 1, null)
            cx.evaluateString(scope, """var window=this; window.lookup={}; window.__cslLayoutDefined={hanging:true,line:true,entry:true}; window.engine=new CSL.Engine({retrieveLocale:function(){return String(spikeLocale);},retrieveItem:function(id){return window.lookup[id];}},String(spikeStyle),'en-US'); window.engine.setOutputFormat('text');""", "setup", 1, null)
            val request = CslBibliography.request(items, clusters)
            val value = cx.evaluateString(scope, "JSON.stringify((function(){${CslBibliography.script(request)}})())", "bibliography", 1, null).toString()
            val result = CslBibliography.parse(value, items, clusters)
            assertEquals("(Smith, 2020a)", result.citations[0].text)
            assertEquals("Smith (2020b)", result.citations[1].text)
            assertEquals(true, result.layout.hangingIndent)
            assertEquals(2.0, result.layout.lineSpacing!!, 0.0)
            assertEquals(0.0, result.layout.entrySpacing!!, 0.0)
            val ids = result.entries.map { it.itemIds.single() }
            val entries = result.entries.map { it.runs }
            assertEquals(items.size, entries.size)
            assertTrue(ids.indexOf("a") < ids.indexOf("b"))
            val reduced = listOf(CitationCluster("only", listOf("a")))
            val second = cx.evaluateString(scope, "JSON.stringify((function(){${CslBibliography.script(CslBibliography.request(items, reduced))}})())", "repeat", 1, null).toString()
            val repeated = CslBibliography.parse(second, items, reduced)
            assertEquals("(Smith, 2020a)", repeated.citations.single().text)
            assertEquals(4, repeated.entries.size)
            val builtIn = BuiltInApaProcessor.bibliography(items, emptyList()).entries.associateBy { it.itemIds.single() }
            val checks = mapOf("a" to "Example Journal", "b" to "Beta", "c" to "A webpage", "d" to "Hindi Journal")
            for ((id, part) in checks) {
                val cslItalic = entries[ids.indexOf(id)].any { it.italic && it.text.contains(part) }
                val builtinItalic = builtIn.getValue(id).runs.any { it.italic && it.text.contains(part) }
                assertEquals("$id italics for $part", builtinItalic, cslItalic)
                assertTrue("$id should contain italic $part", cslItalic)
            }
        } finally { Context.exit() }
    }
}
