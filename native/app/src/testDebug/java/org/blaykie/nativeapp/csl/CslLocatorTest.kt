package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.csl.spike.CslSpike
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class CslLocatorTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")).bufferedReader().use { it.readText() }
    @Test fun rendersPageAndPrefixThroughSharedScript() {
        val request = CslRenderRequest(CslSpike.fixtures(1).map { it as kotlinx.serialization.json.JsonObject }, listOf("item0"), page="12", prefix="see")
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "spikeStyle", asset("apa.csl"))
            ScriptableObject.putProperty(scope, "spikeLocale", asset("locales-en-US.xml"))
            cx.evaluateString(scope, asset("citeproc.js"), "citeproc.js", 1, null)
            cx.evaluateString(scope, """var window=this; window.lookup={}; window.engine=new CSL.Engine({retrieveLocale:function(){return String(spikeLocale);},retrieveItem:function(id){return window.lookup[id];}},String(spikeStyle),'en-US'); window.engine.setOutputFormat('text');""", "setup", 1, null)
            val json = cx.evaluateString(scope, "JSON.stringify((function(){${CslRenderScript.body(request.toJson())}})())", "render", 1, null).toString()
            val result = CslRenderResponse.parse(json, 1)
            assertEquals("(see Author0, 2020, p. 12)", result.cluster)
            assertEquals("see Author0", result.author)
            assertEquals("(2020, p. 12)", result.suppress)
        } finally { Context.exit() }
    }
    @Test fun rejectsAffixesForMultipleItems() {
        val records = CslSpike.fixtures(2).map { it as kotlinx.serialization.json.JsonObject }
        try { CslRenderRequest(records, listOf("item0", "item1"), page="12").toJson(); fail("Expected rejection") }
        catch (expected: IllegalArgumentException) { }
    }
}
