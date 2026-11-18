package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ZoteroCslJsonTest {
    @Test fun parsesSyntheticSamplesWithoutNetwork() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/zotero-synthetic-csljson.json")).bufferedReader().use { it.readText() }
        val records = ZoteroCslJson.parse(text)
        assertEquals(3, records.size)
        assertEquals("Example Group", records[1]["author"]!!.jsonArray[0].jsonObject["literal"]!!.jsonPrimitive.content)
        assertEquals("अनिता", records[2]["author"]!!.jsonArray[0].jsonObject["given"]!!.jsonPrimitive.content)
    }
    @Test fun rejectsMalformedOrDuplicateIds() {
        listOf("{}", "[]", "[{\"id\":1,\"type\":\"book\"}]", "[{\"id\":\"x\",\"type\":\"book\"},{\"id\":\"x\",\"type\":\"book\"}]").forEach { input ->
            try { ZoteroCslJson.parse(input); fail("Invalid input accepted") } catch (expected: Exception) { }
        }
    }
}
