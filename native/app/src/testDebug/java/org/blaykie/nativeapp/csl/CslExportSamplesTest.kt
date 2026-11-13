package org.blaykie.nativeapp.csl

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.blaykie.nativeapp.citation.processor.*
import org.blaykie.nativeapp.docx.*
import org.blaykie.nativeapp.export.ExportCslStyle
import org.blaykie.nativeapp.export.prepareCslExport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/** Uses citeproc in Rhino with the same request and response as the debug WebView. */
class CslExportSamplesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private val authors = listOf(CitationAuthor("Alex", "Example"))
    private val alpha = CitationItem("ALPHA1", "Alpha study", authors, "2020", itemType = "journalArticle", publicationTitle = "Test Journal", volume = "4")
    private val beta = CitationItem("BETA2", "Beta study", authors, "2020", itemType = "book", publisher = "Test Press")
    private val hindi = CitationItem("HINDI3", "हिन्दी अध्ययन", listOf(CitationAuthor("अनिता", "शर्मा")), "2022", itemType = "book", publisher = "उदाहरण प्रकाशन")
    private val old = CitationItem("OLD1", "Earlier reference", listOf(CitationAuthor("Sam", "Earlier")), "2018", itemType = "book", publisher = "Test Press")

    private fun source(legacy: Boolean, heading: Boolean = false): File {
        val original = File(temp.root, "fixture.docx").apply { if (!exists()) writeBytes(resource("m4c/source.docx")) }
        val output = File(temp.root, "source-${legacy}-${heading}.docx")
        ZipFile(original).use { input -> ZipOutputStream(output.outputStream()).use { out ->
            input.entries().asSequence().forEach { entry ->
                val bytes = input.getInputStream(entry).use { it.readBytes() }
                val data = if (entry.name != "word/document.xml") bytes else {
                    val xml = bytes.toString(Charsets.UTF_8)
                    val bodyStart = xml.indexOf("<w:body>") + "<w:body>".length
                    val bodyEnd = xml.indexOf("</w:body>")
                    require(bodyStart >= "<w:body>".length && bodyEnd > bodyStart)
                    fun paragraph(text: String) = "<w:p><w:pPr><w:spacing w:line=\"300\"/></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"
                    val labels = listOf("Parenthetical example.", "Narrative example.", "Page example.", "Prefix example.", "Hindi author example.")
                    val legacyParagraph = if (legacy) "<w:p><w:r><w:t>Earlier citation: </w:t></w:r><w:bookmarkStart w:id=\"1\" w:name=\"BLK_OLD1_1\"/><w:r><w:t xml:space=\"preserve\">(Earlier, 2018)</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p>" else ""
                    val content = labels.joinToString("") { paragraph(it) } + legacyParagraph + (if (heading) paragraph("References") else "") + "<w:sectPr/>"
                    (xml.substring(0, bodyStart) + content + xml.substring(bodyEnd)).toByteArray(Charsets.UTF_8)
                }
                out.putNextEntry(ZipEntry(entry.name)); out.write(data); out.closeEntry()
            }
        } }
        return output
    }
    private fun project(source: File, legacy: Boolean): DocxProject {
        val p = DocxProject(source)
        if (legacy) p.registerItems(listOf(old))
        fun add(block: Int, item: CitationItem, mode: String = "parenthetical", page: String = "", prefix: String = "") {
            val pos = p.blockText(block).length
            p.insertCitation(block, pos, pos, listOf(item), mode, page, prefix)
        }
        add(0, alpha); add(1, beta, "narrative"); add(2, alpha, page = "42"); add(3, beta, prefix = "see"); add(4, hindi)
        return p
    }
    private fun processor(cx: Context, scope: org.mozilla.javascript.Scriptable): CitationProcessor = object : CitationProcessor {
        override suspend fun citation(items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>): String = error("Session rendering required")
        override suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult {
            val request = CslBibliography.request(items, clusters)
            val value = cx.evaluateString(scope, "JSON.stringify((function(){${CslBibliography.script(request)}})())", "export", 1, null).toString()
            return CslBibliography.parse(value, items, clusters)
        }
    }
    private fun compareParts(source: File, output: File) = ZipFile(source).use { a -> ZipFile(output).use { b ->
        val names = a.entries().asSequence().map { it.name }.toSet()
        assertEquals(names, b.entries().asSequence().map { it.name }.toSet())
        for (name in names.filter { it != "word/document.xml" }) assertArrayEquals(name, a.getInputStream(a.getEntry(name)).use { it.readBytes() }, b.getInputStream(b.getEntry(name)).use { it.readBytes() })
    } }
    @Test fun generateThreeSampleDocuments() = runBlocking {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1; cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "spikeStyle", resource("csl/apa.csl").toString(Charsets.UTF_8))
            ScriptableObject.putProperty(scope, "spikeLocale", resource("csl/locales-en-US.xml").toString(Charsets.UTF_8))
            cx.evaluateString(scope, resource("csl/citeproc.js").toString(Charsets.UTF_8), "citeproc.js", 1, null)
            cx.evaluateString(scope, """var window=this; window.lookup={}; window.__cslLayoutDefined={hanging:true,line:true,entry:true}; window.engine=new CSL.Engine({retrieveLocale:function(){return String(spikeLocale);},retrieveItem:function(id){return window.lookup[id];}},String(spikeStyle),'en-US'); window.engine.setOutputFormat('text');""", "setup", 1, null)
            val style = ExportCslStyle("APA Style 7th edition", processor(cx, scope))
            val out = System.getenv("BLAYKIE_SAMPLE_DIR")?.let(::File) ?: File(temp.root, "samples")
            check(out.isDirectory || out.mkdirs())
            val plain = source(false); val original = plain.readBytes(); val p = project(plain, false)
            val csl = File(out, "a-csl-apa.docx"); val builtin = File(out, "b-builtin-apa.docx")
            assertEquals(0, prepareCslExport(p, csl, "match", false, style))
            p.export(builtin, "match")
            val legacySource = source(true); val legacyOriginal = legacySource.readBytes(); val lp = project(legacySource, true)
            val legacy = File(out, "c-csl-legacy.docx")
            assertEquals(1, prepareCslExport(lp, legacy, "match", false, style))
            for ((input, file) in listOf(plain to csl, plain to builtin, legacySource to legacy)) {
                compareParts(input, file)
                val reopened = DocxProject(file)
                val text = (0 until reopened.blockCount).joinToString(" ") { reopened.blockText(it) }
                assertTrue(text.contains("2020a")); assertTrue(text.contains("2020b")); assertTrue(text.contains("42")); assertTrue(text.contains("see")); assertTrue(text.contains("शर्मा")); assertTrue(text.contains("References"))
                if (file == legacy) assertTrue(text.contains("(Earlier, 2018)"))
            }
            assertArrayEquals(original, plain.readBytes()); assertArrayEquals(legacyOriginal, legacySource.readBytes())
        } finally { Context.exit() }
    }
}
