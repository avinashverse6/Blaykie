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

/** Independent diagnostic runs; every requested stage repeats its prerequisites. */
object StagedSpike {
    enum class Target(val label: String, val count: Int = 0) {
        CONTEXT("Rhino context"), SCRIPT("Load/evaluate citeproc.js"), ENGINE("Create CSL engine"),
        ONE("Render 1 item", 1), FIVE("Render 5 items", 5), FIFTY("Render 50 items", 50), THREE_HUNDRED("Render 300 items", 300)
    }
    data class Event(val stage: String, val status: String, val ms: Long, val heapBytes: Long, val detail: String = "")
    private fun heap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    fun run(target: Target, load: (String) -> String, report: (Event) -> Unit) {
        fun <T> step(name: String, work: () -> T): T {
            val start = System.nanoTime()
            report(Event(name, "start", 0, heap()))
            try {
                val result = work()
                report(Event(name, "done", (System.nanoTime() - start) / 1_000_000, heap()))
                return result
            } catch (t: Throwable) {
                report(Event(name, "error", (System.nanoTime() - start) / 1_000_000, heap(), "${t.javaClass.name}: ${t.message.orEmpty().replace('\n', ' ').take(240)}"))
                throw t
            }
        }
        // Interpreted mode is required on Android. The observer permits cooperative cancellation.
        val factory = object : org.mozilla.javascript.ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
            }
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Diagnostic run interrupted by watchdog")
            }
        }
        var entered = false
        try {
            val cx = step(Target.CONTEXT.label) {
                factory.enterContext().also {
                    entered = true
                    report(Event(Target.CONTEXT.label, "info", 0, heap(), "${it.implementationVersion}; optimization level ${it.optimizationLevel}"))
                }
            }
            if (target == Target.CONTEXT) return
            val scope = step(Target.SCRIPT.label) {
                val s = cx.initStandardObjects()
                cx.evaluateString(s, load("citeproc.js"), "citeproc.js", 1, null)
                s
            }
            if (target == Target.SCRIPT) return
            step(Target.ENGINE.label) {
                ScriptableObject.putProperty(scope, "spikeStyle", load("apa.csl"))
                ScriptableObject.putProperty(scope, "spikeLocale", load("locales-en-US.xml"))
                cx.evaluateString(scope, """
                    var lookup = {}, ids = [];
                    var sys = {retrieveLocale:function(){return String(spikeLocale);}, retrieveItem:function(id){return lookup[id];}};
                    var engine = new CSL.Engine(sys, String(spikeStyle), 'en-US');
                    engine.setOutputFormat('text');
                """.trimIndent(), "engine.js", 1, null)
            }
            if (target == Target.ENGINE) return
            step(target.label) {
                ScriptableObject.putProperty(scope, "spikeItems", CslSpike.fixtures(target.count).toString())
                val output = cx.evaluateString(scope, """
                    var records = JSON.parse(String(spikeItems));
                    records.forEach(function(item){lookup[item.id]=item;ids.push(item.id);});
                    engine.updateItems(ids);
                    var citation = engine.makeCitationCluster(ids.slice(0,5).map(function(id){return {id:id};}));
                    var bibliography = engine.makeBibliography();
                    JSON.stringify({citation:citation, count:bibliography[1].length});
                """.trimIndent(), "render.js", 1, null).toString()
                val result = Json.parseToJsonElement(output).jsonObject
                check(result.getValue("citation").jsonPrimitive.content.isNotBlank())
                check(result.getValue("count").jsonPrimitive.int == target.count)
            }
        } finally {
            if (entered) Context.exit()
        }
    }
}
