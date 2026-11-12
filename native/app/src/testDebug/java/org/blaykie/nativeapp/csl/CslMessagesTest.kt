package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CslMessagesTest {
    private val record = buildJsonObject { put("id", "one"); put("type", "book"); put("title", "A title") }
    private fun invalid(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (expected: Exception) { } }

    @Test fun validatesRequest() {
        val json = CslRenderRequest(listOf(record), listOf("one")).toJson()
        assertEquals("one", Json.parseToJsonElement(json).jsonObject["citationIds"]!!.jsonArray[0].jsonPrimitive.content)
        invalid { CslRenderRequest(emptyList(), listOf("one")).toJson() }
        invalid { CslRenderRequest(listOf(record, record), listOf("one")).toJson() }
        invalid { CslRenderRequest(listOf(record), listOf("missing")).toJson() }
        invalid { CslRenderRequest(listOf(buildJsonObject { put("id", 3); put("type", "book") }), listOf("3")).toJson() }
        invalid { CslRenderRequest(listOf(buildJsonObject { put("id", "one"); put("type", "book"); put("title", "x".repeat(2_000_001)) }), listOf("one")).toJson() }
    }

    @Test fun validatesResponse() {
        val valid = """{"ok":true,"cluster":"(A, 2020)","author":"A","suppress":"(2020)","andTerm":"and","bibliography":["A. (2020)."]}"""
        assertEquals("A", CslRenderResponse.parse(valid, 1).author)
        invalid { CslRenderResponse.parse(valid, 2) }
        invalid { CslRenderResponse.parse(valid.replace("\"author\":\"A\"", "\"author\":5"), 1) }
        invalid { CslRenderResponse.parse(valid.replace("\"ok\":true", "\"ok\":false"), 1) }
        invalid { CslRenderResponse.parse(valid.replace("\"ok\":true", "\"ok\":true,\"extra\":1"), 1) }
        invalid { CslRenderResponse.parse("x".repeat(2_000_001), 1) }
    }
}
