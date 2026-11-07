package org.blaykie.nativeapp.zotero

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.docx.CitationFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationGoldenTest {
    private fun JsonObject.str(key: String) = (getValue(key) as JsonPrimitive).content
    private fun JsonObject.keys(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

    @Test fun organizationPythonSweep() {
        val json = javaClass.classLoader!!.getResourceAsStream("organization-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val golden = Json.parseToJsonElement(json) as JsonObject
        val items = (golden.getValue("items") as JsonArray).associate { value ->
            val obj = value as JsonObject
            obj.str("key") to ZoteroItem(obj.str("key"), obj.getValue("data") as JsonObject).citationItem()
        }
        val errors = mutableListOf<String>()
        for (value in golden.getValue("cases") as JsonArray) {
            val c = value as JsonObject; val key = c.str("key"); val item = items.getValue(key)
            val expected = (c.getValue("reference_parts") as JsonArray).map { value ->
                val pair = value as JsonArray; (pair[0] as JsonPrimitive).content to (pair[1] as JsonPrimitive).boolean
            }
            val actual = CitationFormatting.reference(item)
            val text = actual.joinToString("") { it.first }.trim()
            if (actual != expected) errors += "$key parts: expected $expected actual $actual"
            if (text != c.str("reference")) errors += "$key reference: expected ${c.str("reference")} actual $text"
            val parenthetical = CitationFormatting.inText(listOf(item))
            val narrative = CitationFormatting.citationText(listOf(item), "narrative")
            if (parenthetical != c.str("parenthetical")) errors += "$key parenthetical: expected ${c.str("parenthetical")} actual $parenthetical"
            if (narrative != c.str("narrative")) errors += "$key narrative: expected ${c.str("narrative")} actual $narrative"
            if (item.authors.isEmpty() || item.authors.any { it.last.isBlank() || it.last == "." || it.last == "null" }) errors += "$key invalid author: ${item.authors}"
            if (text.contains("null")) errors += "$key contains null: $text"
        }
        val ordered = CitationFormatting.ordered(golden.keys("orderKeys").map { items.getValue(it) }).map { it.key }
        if (ordered != golden.keys("order")) errors += "order: expected ${golden.keys("order")} actual $ordered"
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun noAuthorStillUsesTitle() {
        val d = Json.parseToJsonElement("""{"itemType":"book","title":"Health report","date":"2020","creators":[],"publisher":"World Health Organization"}""") as JsonObject
        val item = ZoteroItem("none", d).citationItem()
        assertEquals("(Health Report, 2020)", CitationFormatting.inText(listOf(item)))
        assertEquals("Health Report (2020)", CitationFormatting.citationText(listOf(item), "narrative"))
        assertEquals("Health report. (2020). World Health Organization.", CitationFormatting.reference(item).joinToString("") { it.first }.trim())
    }
}
