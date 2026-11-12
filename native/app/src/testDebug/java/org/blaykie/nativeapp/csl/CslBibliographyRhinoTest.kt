package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.citation.processor.BuiltInApaProcessor
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
            ScriptableObject.putProperty(scope, "records", JsonArray(items.map(CitationItemToCsl::convert)).toString())
            cx.evaluateString(scope, asset("citeproc.js"), "citeproc.js", 1, null)
            val value = cx.evaluateString(scope, """
                var records=JSON.parse(String(records)), lookup={};
                records.forEach(function(item){lookup[item.id]=item;});
                var engine=new CSL.Engine({retrieveLocale:function(){return String(spikeLocale);},retrieveItem:function(id){return lookup[id];}},String(spikeStyle),'en-US');
                var citations=records.map(function(item,i){return {citationID:'c'+i,citationItems:[{id:item.id}],properties:{noteIndex:0}};});
                var rendered=engine.rebuildProcessorState(citations,'text',[]);
                engine.setOutputFormat('html'); var bibliography=engine.makeBibliography();
                JSON.stringify({citations:rendered, meta:bibliography[0], entries:bibliography[1]});
            """.trimIndent(), "bibliography", 1, null).toString()
            val root = Json.parseToJsonElement(value).jsonObject
            val citations = root.getValue("citations").jsonArray
            assertEquals("(Smith, 2020a)", citations[0].jsonArray[2].jsonPrimitive.content)
            assertEquals("(Smith, 2020b)", citations[1].jsonArray[2].jsonPrimitive.content)
            val meta = root.getValue("meta").jsonObject
            assertTrue(meta.getValue("hangingindent").jsonPrimitive.boolean)
            val ids = meta.getValue("entry_ids").jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
            val entries = root.getValue("entries").jsonArray.map { CslRichText.parse(it.jsonPrimitive.content) }
            assertEquals(items.size, entries.size)
            assertTrue(ids.indexOf("a") < ids.indexOf("b"))
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
