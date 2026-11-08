package org.blaykie.nativeapp.zotero

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.docx.CitationFormatting
import org.junit.Assert.assertEquals
import org.junit.Test

class ApaRound2Test {
    private fun JsonObject.str(key: String) = (getValue(key) as JsonPrimitive).content
    private fun JsonObject.keys(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

    @Test fun pythonGolden() {
        val text = javaClass.classLoader!!.getResourceAsStream("apa-round2-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val golden = Json.parseToJsonElement(text) as JsonObject
        val items = (golden.getValue("items") as JsonArray).associate { element ->
            val obj = element as JsonObject
            obj.str("key") to ZoteroItem(obj.str("key"), obj.getValue("data") as JsonObject).citationItem()
        }
        for (element in golden.getValue("cases") as JsonArray) {
            val c = element as JsonObject; val item = items.getValue(c.str("key")); val key = item.key
            assertEquals("$key year", c.str("year"), CitationFormatting.displayYear(item))
            assertEquals("$key citation", c.str("parenthetical"), CitationFormatting.inText(listOf(item)))
            assertEquals("$key narrative", c.str("narrative"), CitationFormatting.citationText(listOf(item), "narrative"))
            val expected = (c.getValue("reference_parts") as JsonArray).map { part ->
                val pair = part as JsonArray; (pair[0] as JsonPrimitive).content to (pair[1] as JsonPrimitive).boolean
            }
            val actual = CitationFormatting.reference(item)
            // The legacy journal builder may split neighboring runs without changing formatting.
            val merged = mutableListOf<Pair<String, Boolean>>()
            for ((t, italic) in actual) if (t.isNotEmpty()) {
                if (merged.isNotEmpty() && merged.last().second == italic) merged[merged.lastIndex] = (merged.last().first + t) to italic else merged.add(t to italic)
            }
            if (merged.isNotEmpty()) { merged[0] = merged[0].first.trimStart() to merged[0].second; merged[merged.lastIndex] = merged.last().first.trimEnd() to merged.last().second }
            assertEquals("$key parts", expected, merged.filter { it.first.isNotEmpty() })
        }
        for (element in golden.getValue("combined") as JsonArray) {
            val c = element as JsonObject; val group = c.keys("keys").map { items.getValue(it) }
            assertEquals(c.keys("order"), CitationFormatting.ordered(group).map { it.key })
            assertEquals(c.str("parenthetical"), CitationFormatting.inText(group))
            assertEquals(c.str("narrative"), CitationFormatting.citationText(group, "narrative"))
            assertEquals(c.str("references"), CitationFormatting.referenceList(group))
        }
        for (element in golden.getValue("contexts") as JsonArray) {
            val c = element as JsonObject
            assertEquals(c.str("expected"), CitationFormatting.citationText(listOf(items.getValue(c.str("key"))), c.str("style"), c.str("page"), c.str("prefix"), c.keys("context").map { items.getValue(it) }))
        }
    }
}
