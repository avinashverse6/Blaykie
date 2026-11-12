package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.docx.DocxProject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class CslInsertionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")).bufferedReader().use { it.readText() }

    @Test fun rendersAndInsertsCitationPreviewsAndUndoRestoresParagraph() {
        val file = temp.newFile("sample.docx")
        file.writeBytes(requireNotNull(javaClass.classLoader?.getResourceAsStream("edit-parity.docx")).use { it.readBytes() })
        val project = DocxProject(file)
        val base = project.blockText(0)
        val authors = listOf(CitationAuthor("Alex", "Smith"), CitationAuthor("Jo", "Jones"))
        val twoAuthors = CitationItem("one", "Example", authors, "2020")
        val threeAuthors = twoAuthors.copy(key="two", authors=authors + CitationAuthor("Rina", "Patel"))
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "spikeStyle", asset("apa.csl"))
            ScriptableObject.putProperty(scope, "spikeLocale", asset("locales-en-US.xml"))
            cx.evaluateString(scope, asset("citeproc.js"), "citeproc.js", 1, null)
            cx.evaluateString(scope, """var window=this; window.lookup={}; window.engine=new CSL.Engine({retrieveLocale:function(){return String(spikeLocale);},retrieveItem:function(id){return window.lookup[id];}},String(spikeStyle),'en-US'); window.engine.setOutputFormat('text');""", "setup", 1, null)
            fun render(item: CitationItem, mode: String, page: String = "", prefix: String = ""): String {
                val request = CitationItemToCsl.request(listOf(item), listOf(item)).copy(page=page, prefix=prefix)
                val json = cx.evaluateString(scope, "JSON.stringify((function(){${CslRenderScript.body(request.toJson())}})())", "render", 1, null).toString()
                val result = CslRenderResponse.parse(json, 1)
                return if (mode == "narrative") "${narrativeAuthor(result.author, result.andTerm, item.authors.size)} ${result.suppress}" else result.cluster
            }
            val cases = listOf(
                Triple(twoAuthors, "parenthetical", render(twoAuthors, "parenthetical")),
                Triple(twoAuthors, "parenthetical", render(twoAuthors, "parenthetical", "12", "see")),
                Triple(twoAuthors, "narrative", render(twoAuthors, "narrative", "12")),
                Triple(threeAuthors, "narrative", render(threeAuthors, "narrative")),
            )
            val expected = listOf(
                CitationFormatting.citationText(listOf(twoAuthors)),
                CitationFormatting.citationText(listOf(twoAuthors), "parenthetical", "12", "see"),
                CitationFormatting.citationText(listOf(twoAuthors), "narrative", "12"),
                CitationFormatting.citationText(listOf(threeAuthors), "narrative"),
            )
            cases.forEachIndexed { index, (item, mode, text) ->
                assertEquals(expected[index], text)
                project.insertCitation(0, base.length, base.length, listOf(item), style=mode, previewText=text)
                assertTrue(project.blockText(0).endsWith(text))
                assertTrue(project.undo())
                assertEquals(base, project.blockText(0))
            }
            assertEquals("see Smith and Jones (2020, p. 12)", render(twoAuthors, "narrative", "12", "see"))
            project.insertCitation(0, base.length, base.length, listOf(twoAuthors), previewText="(First, 2020)")
            val first = project.blockText(0)
            project.insertCitation(0, first.length, first.length, listOf(threeAuthors), previewText="(Second, 2020)")
            assertTrue(project.blockText(0).startsWith(first))
            assertTrue(project.undo()); assertEquals(first, project.blockText(0))
        } finally { Context.exit() }
    }
}
