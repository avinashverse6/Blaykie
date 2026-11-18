package org.blaykie.nativeapp.csl

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class StyleDependentTest {
    @get:Rule val temp = TemporaryFolder()
    private val apaId = "http://www.zotero.org/styles/apa"
    private fun bytes(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private fun child(parent: String, locale: String = "en-US") = """<style xmlns="http://purl.org/net/xbiblio/csl" default-locale="$locale"><info><title>Example dependent edition</title><id>http://example.test/child</id><link rel="independent-parent" href="$parent"/></info></style>"""
    private fun parent(id: String) = """<style xmlns="http://purl.org/net/xbiblio/csl" class="in-text"><info><title>Example parent</title><id>$id</id><category citation-format="author-date"/></info></style>"""

    @Test fun missingParentIsRejectedAndRemovalDisablesChild() {
        val dir = File(temp.root, "styles")
        val parentId = "http://example.test/parent"
        val importer = StyleImportStore(dir, available = {
            if (!dir.exists()) emptyList() else dir.listFiles()!!.filter { it.extension == "csl" }.map { it.inputStream().use { stream -> StyleRegistry.read(it.name, stream, StyleSource.PRIVATE) } }
        })
        try { importer.import(child(parentId).byteInputStream()); fail("missing parent accepted") }
        catch (e: StyleImportFailure) { assertTrue(e.message!!.contains(parentId)); assertTrue(e.message!!.contains("import the parent file first")) }
        val p = importer.import(parent(parentId).byteInputStream())
        val c = importer.import(child(parentId).byteInputStream())
        assertEquals(parentId, c.parentId); assertEquals("en-US", c.defaultLocale)
        assertNull(styleChoices(listOf(p, c)).last().disabledReason)
        assertTrue(importer.remove(p.asset)); assertTrue(File(dir, c.asset).exists())
        val unavailable = styleChoices(listOf(c)).last().disabledReason
        assertTrue(unavailable!!.contains(parentId)); assertTrue(unavailable.contains("import the parent file first"))
    }

    @Test fun bundledParentIsUsedByRhinoAndChildMetadataIsRetained() {
        val bundled = StyleRegistry.load({ name -> bytes(name).inputStream() }).single()
        val dir = File(temp.root, "styles")
        val importer = StyleImportStore(dir, available = { listOf(bundled) })
        val dependent = importer.import(child(apaId).byteInputStream())
        val resolved = effectiveStyle(dependent, listOf(bundled, dependent)) { m ->
            if (m.source == StyleSource.BUNDLED) bytes(m.asset).inputStream() else File(dir, m.asset).inputStream()
        }
        assertEquals(bytes("csl/apa.csl").toString(Charsets.UTF_8), resolved.text)
        assertEquals("Example dependent edition", resolved.title); assertEquals("en-US", resolved.defaultLocale)
        assertNull(styleChoices(listOf(bundled, dependent)).last().disabledReason)
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "parentXml", resolved.text)
            ScriptableObject.putProperty(scope, "localeXml", bytes("csl/locales-en-US.xml").toString(Charsets.UTF_8))
            ScriptableObject.putProperty(scope, "language", resolved.defaultLocale)
            cx.evaluateString(scope, bytes("csl/citeproc.js").toString(Charsets.UTF_8), "citeproc", 1, null)
            val result = cx.evaluateString(scope, """var item={id:'x',type:'book',title:'Sample',author:[{family:'Example',given:'Alex'}],issued:{'date-parts':[[2020]]}}; var e=new CSL.Engine({retrieveLocale:function(){return String(localeXml);},retrieveItem:function(){return item;}},String(parentXml),String(language),true); e.updateItems(['x']); e.makeCitationCluster([{id:'x'}]);""", "test", 1, null).toString()
            assertEquals("(Example, 2020)", result)
        } finally { Context.exit() }
    }
}
