package org.blaykie.nativeapp.citation.processor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInApaProcessorTest {
    private fun JsonObject.str(key: String) = (getValue(key) as JsonPrimitive).content
    private fun JsonObject.keys(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

    @Test fun delegatesUnchangedAcrossExistingGoldenCorpus() = runBlocking {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("apa-round2-golden.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val golden = Json.parseToJsonElement(text) as JsonObject
        val items = (golden.getValue("items") as JsonArray).associate { element ->
            val obj = element as JsonObject
            obj.str("key") to ZoteroItem(obj.str("key"), obj.getValue("data") as JsonObject).citationItem()
        }
        for (element in golden.getValue("cases") as JsonArray) {
            val c = element as JsonObject
            val selected = listOf(items.getValue(c.str("key")))
            for (mode in listOf("parenthetical", "narrative")) {
                assertEquals(c.str("key") + " " + mode, c.str(mode), BuiltInApaProcessor.citation(selected, mode))
                assertEquals(CitationFormatting.citationText(selected, mode, "42", "see"),
                    BuiltInApaProcessor.citation(selected, mode, "42", "see"))
            }
        }
        for (element in golden.getValue("combined") as JsonArray) {
            val c = element as JsonObject
            val selected = c.keys("keys").map { items.getValue(it) }
            for (mode in listOf("parenthetical", "narrative")) {
                assertEquals(c.str(mode), BuiltInApaProcessor.citation(selected, mode))
            }
        }
        for (element in golden.getValue("contexts") as JsonArray) {
            val c = element as JsonObject
            assertEquals(c.str("expected"), BuiltInApaProcessor.citation(
                listOf(items.getValue(c.str("key"))), c.str("style"), c.str("page"), c.str("prefix"),
                c.keys("context").map { items.getValue(it) }))
        }
    }
}
