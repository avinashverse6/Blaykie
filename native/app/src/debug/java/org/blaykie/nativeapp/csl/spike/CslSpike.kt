package org.blaykie.nativeapp.csl.spike

import kotlinx.serialization.json.*
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/** Debug-only experiment. Inputs are local, fixed fixtures, not untrusted code. */
object CslSpike {
    data class Result(val parenthetical: String, val authorOnly: String, val suppressAuthor: String, val bibliography: List<String>, val renderMs: Long)
    fun fixtures(count: Int): JsonArray = buildJsonArray {
        repeat(count) { i ->
            val type = listOf("article-journal", "book", "webpage", "report", "article-journal")[i % 5]
            add(buildJsonObject {
                put("id", "item$i"); put("type", type); put("title", "Example title ${i + 1}")
                put("author", buildJsonArray { add(buildJsonObject {
                    put("family", if (i % 5 == 4) "शर्मा" else "Author$i")
                    put("given", if (i % 5 == 4) "अनिता" else "Alex")
                }) })
                put("issued", buildJsonObject { put("date-parts", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(2020 + i % 4)) }) }) })
                if (type == "article-journal") { put("container-title", "Example Journal"); put("volume", "2"); put("page", "10-20") }
                if (type == "book" || type == "report") put("publisher", "Example Press")
                if (type == "webpage") put("URL", "https://example.org/page")
            })
        }
    }
    fun run(js: String, style: String, locale: String, items: JsonArray): Result {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            fun put(name: String, value: String) = ScriptableObject.putProperty(scope, name, value)
            put("spikeStyle", style); put("spikeLocale", locale); put("spikeItems", items.toString())
            cx.evaluateString(scope, js, "citeproc.js", 1, null)
            val output = cx.evaluateString(scope, """
                var records = JSON.parse(String(spikeItems));
                var lookup = {}; var ids = [];
                records.forEach(function (item) { lookup[item.id] = item; ids.push(item.id); });
                var sys = {retrieveLocale: function () {return String(spikeLocale);}, retrieveItem: function(id) {return lookup[id];}};
                var engine = new CSL.Engine(sys, String(spikeStyle), 'en-US');
                engine.setOutputFormat('text'); var renderStart = Date.now(); engine.updateItems(ids);
                var cluster = ids.slice(0, 5).map(function(id) {return {id:id};});
                var parenthetical = engine.makeCitationCluster(cluster);
                var authorOnly = engine.makeCitationCluster([{id:ids[0], 'author-only':true}]);
                var suppressAuthor = engine.makeCitationCluster([{id:ids[0], 'suppress-author':true}]);
                var bib = engine.makeBibliography();
                JSON.stringify({parenthetical:parenthetical,authorOnly:authorOnly,suppressAuthor:suppressAuthor,bibliography:bib[1],renderMs:Date.now()-renderStart});
            """.trimIndent(), "spike-adapter.js", 1, null).toString()
            val json = Json.parseToJsonElement(output).jsonObject
            return Result(json.getValue("parenthetical").jsonPrimitive.content, json.getValue("authorOnly").jsonPrimitive.content,
                json.getValue("suppressAuthor").jsonPrimitive.content, json.getValue("bibliography").jsonArray.map { it.jsonPrimitive.content.trim() }, json.getValue("renderMs").jsonPrimitive.long)
        } finally { Context.exit() }
    }
}
