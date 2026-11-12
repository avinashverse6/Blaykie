package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*

/** Transport model for the spike-style render operation, not a CitationItem converter. */
internal data class CslRenderRequest(val records: List<JsonObject>, val citationIds: List<String>) {
    fun toJson(): String {
        require(records.size in 1..300) { "Expected 1 to 300 records" }
        require(citationIds.size in 1..5) { "Expected 1 to 5 citation ids" }
        val ids = records.map { record ->
            requireString(record, "type", 256)
            requireString(record, "id", 256)
        }
        require(ids.distinct().size == ids.size) { "Duplicate record id" }
        require(citationIds.distinct().size == citationIds.size && citationIds.all { it in ids }) { "Invalid citation ids" }
        val data = buildJsonObject {
            put("records", JsonArray(records))
            put("citationIds", JsonArray(citationIds.map(::JsonPrimitive)))
        }.toString()
        require(data.length <= MAX_MESSAGE) { "Request too large" }
        return data
    }
}

internal data class CslRenderResponse(val cluster: String, val author: String, val suppress: String, val bibliography: List<String>) {
    companion object {
        fun parse(text: String, expectedCount: Int): CslRenderResponse {
            require(text.length <= MAX_MESSAGE) { "Response too large" }
            require(expectedCount in 1..300) { "Invalid expected count" }
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Response must be an object")
            require(root.keys == setOf("ok", "cluster", "author", "suppress", "bibliography")) { "Unexpected response fields" }
            require(root["ok"] == JsonPrimitive(true)) { "Render failed" }
            val bib = root["bibliography"] as? JsonArray ?: error("Bibliography must be an array")
            require(bib.size == expectedCount) { "Unexpected bibliography size" }
            return CslRenderResponse(requireString(root, "cluster", MAX_TEXT), requireString(root, "author", MAX_TEXT),
                requireString(root, "suppress", MAX_TEXT), bib.map { stringValue(it, MAX_TEXT) })
        }
    }
}

private const val MAX_MESSAGE = 2_000_000
private const val MAX_TEXT = 100_000
private fun requireString(obj: JsonObject, key: String, limit: Int): String =
    stringValue(obj[key] ?: error("Missing $key"), limit).also { require(it.isNotBlank()) { "Empty $key" } }
private fun stringValue(value: JsonElement, limit: Int): String {
    val primitive = value as? JsonPrimitive ?: error("Expected string")
    require(primitive.isString && primitive.content.length <= limit) { "Invalid string" }
    return primitive.content
}
