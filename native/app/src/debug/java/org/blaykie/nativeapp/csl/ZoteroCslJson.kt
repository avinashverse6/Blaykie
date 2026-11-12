package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*

/** Parses CSL-JSON already obtained by the caller; this does not make network requests. */
internal object ZoteroCslJson {
    fun parse(text: String): List<JsonObject> {
        require(text.length <= 2_000_000) { "CSL JSON too large" }
        val array = Json.parseToJsonElement(text) as? JsonArray ?: throw IllegalArgumentException("Expected CSL JSON array")
        require(array.size in 1..300) { "Expected 1 to 300 CSL items" }
        val items = array.map { it as? JsonObject ?: throw IllegalArgumentException("Expected CSL item object") }
        // The common request validator checks IDs, types, uniqueness and encoded size.
        val ids = items.map {
            val id = it["id"] as? JsonPrimitive ?: throw IllegalArgumentException("Missing CSL id")
            require(id.isString && id.content.isNotBlank()) { "Invalid CSL id" }
            id.content
        }
        CslRenderRequest(items, ids.take(5)).toJson()
        return items
    }
}
