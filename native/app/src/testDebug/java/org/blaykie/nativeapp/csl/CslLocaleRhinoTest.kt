package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class CslLocaleRhinoTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")).bufferedReader().use { it.readText() }
    @Test fun primaryTermsOverrideEnglishAndMissingTermsFallBack() {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            val style = """<style xmlns="http://purl.org/net/xbiblio/csl" version="1.0" class="in-text"><info><title>Test</title><id>test</id></info><citation et-al-min="3" et-al-use-first="1"><layout delimiter="; "><names variable="author"><name and="text" delimiter=", "/></names><text term="page" prefix=" "/></layout></citation></style>"""
            val primary = """<locale xmlns="http://purl.org/net/xbiblio/csl" version="1.0" xml:lang="xx-XX"><terms><term name="and">zand</term><term name="et-al">zothers</term></terms></locale>"""
            ScriptableObject.putProperty(scope, "styleText", style)
            ScriptableObject.putProperty(scope, "primaryText", primary)
            ScriptableObject.putProperty(scope, "fallbackText", asset("locales-en-US.xml"))
            cx.evaluateString(scope, asset("citeproc.js"), "citeproc", 1, null)
            cx.evaluateString(scope, """var window=this; window.lookup={two:{id:'two',type:'book',author:[{family:'Smith'},{family:'Jones'}]},three:{id:'three',type:'book',author:[{family:'Smith'},{family:'Jones'},{family:'Brown'}]}};""", "items", 1, null)
            fun render(lang: String): Pair<String, String> {
                cx.evaluateString(scope, "var data={style:String(styleText),language:'$lang',primary:String(${if (lang == "en-US") "fallbackText" else "primaryText"}),fallback:String(fallbackText)}; ${CslLocaleScript.createEngine} window.engine.setOutputFormat('text'); window.engine.updateItems(['two','three']);", "setup", 1, null)
                val two = cx.evaluateString(scope, "window.engine.makeCitationCluster([{id:'two'}])", "two", 1, null).toString()
                val three = cx.evaluateString(scope, "window.engine.makeCitationCluster([{id:'three'}])", "three", 1, null).toString()
                return two to three
            }
            val english = render("en-US"); val madeUp = render("xx-XX")
            assertTrue(english.first, english.first.contains("and"))
            assertTrue(english.second, english.second.contains("et al."))
            assertTrue(madeUp.first, madeUp.first.contains("zand"))
            assertTrue(madeUp.second, madeUp.second.contains("zothers"))
            assertTrue(madeUp.first, madeUp.first.contains("page"))
            assertNotEquals(english, madeUp)
        } finally { Context.exit() }
    }
}
