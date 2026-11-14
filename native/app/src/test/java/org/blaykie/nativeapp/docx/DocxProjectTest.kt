package org.blaykie.nativeapp.docx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DocxProjectTest {
    @get:Rule val temp = TemporaryFolder()

    private val first = "First paragraph about citations."
    private val second = "Second paragraph with enough words to edit later on."
    private val item = CitationItem(
        key = "ABCD2345", title = "Citation Studies in Practice",
        authors = listOf(CitationAuthor("Ada", "Lovelace")), date = "2020",
        publicationTitle = "Journal of Testing", volume = "12", issue = "3",
        pages = "1-10", doi = "10.1234/test.2020",
    )

    private fun makeDocx(body: String = "<w:p><w:r><w:t>$first</w:t></w:r></w:p><w:p><w:r><w:t>$second</w:t></w:r></w:p>"): File {
        val file = temp.newFile()
        val document = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf(
                "[Content_Types].xml" to "types".toByteArray(),
                "word/document.xml" to document.toByteArray(Charsets.UTF_8),
                "word/styles.xml" to "<styles>original &amp; styles</styles>".toByteArray(),
                "word/media/image.bin" to byteArrayOf(0, 1, -1, 9, 42),
            ).forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        return file
    }
    private fun output() = File(temp.root, "output.docx")
    private fun zipContent(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        val map = linkedMapOf<String, ByteArray>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) { val e = entries.nextElement(); map[e.name] = zip.getInputStream(e).use { it.readBytes() } }
        map
    }
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test fun pythonGoldenDeletingMultiCitationRemovesAllReferencesAndMatchesExport() {
        val fixture = Json.parseToJsonElement(resource("nested-delete-golden.json").toString(Charsets.UTF_8)) as JsonObject
        fun value(name: String) = (fixture[name] as JsonPrimitive).content
        val items = (fixture["items"] as JsonArray).map { value ->
            val item = value as JsonObject
            ZoteroItem((item["key"] as JsonPrimitive).content, item["data"] as JsonObject).citationItem()
        }
        val source = File(temp.root, "original.docx").apply { writeBytes(resource("m4c/source.docx")) }
        val bytes = source.readBytes(); val project = DocxProject(source); val before = value("before")
        assertEquals(before, project.blockText(1))
        project.insertCitation(1, before.length, before.length, items)
        assertEquals(value("inserted"), project.blockText(1)); assertEquals(setOf("ONE1", "TWO2"), project.presentKeys())
        assertTrue(project.applyTextEdit(1, before))
        assertEquals(value("after"), project.blockText(1)); assertTrue(project.presentKeys().isEmpty())
        val exported = output(); project.export(exported)
        val xml = zipContent(exported).getValue("word/document.xml").toString(Charsets.UTF_8)
        assertEquals(value("document_xml"), xml); assertFalse(xml.contains("BLK_BIBLIOGRAPHY")); assertArrayEquals(bytes, source.readBytes())
        assertTrue(project.undo()); assertEquals(setOf("ONE1", "TWO2"), project.presentKeys())
        assertTrue(project.undo()); assertTrue(project.presentKeys().isEmpty())
    }
    @Test fun deletingMultiCitationRangeRemovesAllNestedBookmarks() {
        val fixture = Json.parseToJsonElement(resource("nested-delete-golden.json").toString(Charsets.UTF_8)) as JsonObject
        val items = (fixture["items"] as JsonArray).map { value ->
            val item = value as JsonObject
            ZoteroItem((item["key"] as JsonPrimitive).content, item["data"] as JsonObject).citationItem()
        }
        val source = File(temp.root, "source.docx").apply { writeBytes(resource("m4c/source.docx")) }
        val bytes = source.readBytes(); val project = DocxProject(source); val before = project.blockText(1)
        project.insertCitation(1, before.length, before.length, items)
        project.deleteRange(1, before.length + 1, project.blockText(1).length)
        assertEquals(before, project.blockText(1)); assertTrue(project.presentKeys().isEmpty())
        val exported = output(); project.export(exported)
        val xml = zipContent(exported).getValue("word/document.xml").toString(Charsets.UTF_8)
        assertFalse(xml.contains("BLK_BIBLIOGRAPHY")); assertArrayEquals(bytes, source.readBytes())
    }
    @Test fun insertCitation() {
        val project = DocxProject(makeDocx())
        assertEquals(1, project.insertCitation(0, first.length, first.length, listOf(item)))
        assertEquals("$first (Lovelace, 2020)", project.blockText(0))
        assertEquals(setOf("ABCD2345"), project.presentKeys())
        assertEquals(0, project.legacyCitationCount())
        assertEquals(listOf(Citation(1, listOf("ABCD2345"), "(Lovelace, 2020)")), project.citations())
    }
    @Test fun documentWideCitationInputsFollowDocumentOrderAndSkipDeletedCitations() {
        val project = DocxProject(makeDocx())
        project.insertCitation(1, second.length, second.length, listOf(item), page = "14", prefix = "see")
        project.insertCitation(0, first.length, first.length, listOf(item), style = "narrative")
        val input = project.citationExportInput()
        assertEquals(listOf("2", "1"), input.clusters.map { it.id })
        assertEquals("narrative", input.clusters.first().mode)
        assertEquals("14", input.clusters.last().page)
        assertEquals("see", input.clusters.last().prefix)
        assertEquals(listOf(item), input.items)
        assertEquals(0, input.legacyCount)
        project.deleteRange(0, first.length + 1, project.blockText(0).length)
        assertEquals(listOf("1"), project.citationExportInput().clusters.map { it.id })
    }
    @Test fun structuredExportPreservesPartsAndUsesPanelSpacingAndRichRuns() {
        val source = makeDocx(); val before = source.readBytes(); val project = DocxProject(source)
        project.insertCitation(0, first.length, first.length, listOf(item))
        val input = project.citationExportInput()
        val result = org.blaykie.nativeapp.citation.processor.BibliographyResult(
            listOf(org.blaykie.nativeapp.citation.processor.BibliographyEntry(listOf(item.key), listOf(
                org.blaykie.nativeapp.citation.processor.RichRun("Styled & title", italic = true, bold = true, underline = true, smallCaps = true)))),
            org.blaykie.nativeapp.citation.processor.BibliographyLayout(hangingIndent = true, lineSpacing = 2.0),
            listOf(org.blaykie.nativeapp.citation.processor.ClusterResult(input.clusters.single().id, "(Custom & citation)")))
        val file = output(); project.exportWithProcessor(file, "1.5", false, result)
        DocxProject(file)
        val originalParts = zipContent(source); val parts = zipContent(file)
        assertArrayEquals(before, source.readBytes())
        originalParts.filterKeys { it != "word/document.xml" }.forEach { (name, bytes) -> assertArrayEquals(bytes, parts.getValue(name)) }
        val xml = parts.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("(Custom &amp; citation)")); assertTrue(xml.contains("Styled &amp; title"))
        assertTrue(xml.contains("<w:i/>")); assertTrue(xml.contains("<w:b/>")); assertTrue(xml.contains("<w:u w:val=\"single\"/>")); assertTrue(xml.contains("<w:smallCaps/>"))
        assertTrue(xml.contains("w:hanging=\"720\"")); assertTrue(xml.contains("w:line=\"360\""))
        try { project.buildXmlWithProcessor("match", false, result.copy(citations = emptyList())); fail("missing result accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun bibliographyLayoutHintsControlIndentAndEntrySpacing() {
        val project = DocxProject(makeDocx())
        project.insertCitation(0, first.length, first.length, listOf(item))
        val input = project.citationExportInput()
        val result = org.blaykie.nativeapp.citation.processor.BibliographyResult(
            listOf(org.blaykie.nativeapp.citation.processor.BibliographyEntry(listOf(item.key), listOf(org.blaykie.nativeapp.citation.processor.RichRun("Reference")))),
            org.blaykie.nativeapp.citation.processor.BibliographyLayout(hangingIndent = true, entrySpacing = 1.0, lineSpacing = 2.0),
            listOf(org.blaykie.nativeapp.citation.processor.ClusterResult(input.clusters.single().id, "(Example, 2020)")))
        fun entry(layout: org.blaykie.nativeapp.citation.processor.BibliographyLayout): String {
            val xml = project.buildXmlWithProcessor("1.5", false, result.copy(layout = layout))
            return Regex("<w:p><w:pPr>(.*?)</w:pPr>(.*?)Reference</w:t></w:r></w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml).last().value
        }
        val withHint = entry(result.layout)
        assertTrue(withHint.contains("<w:ind w:left=\"720\" w:hanging=\"720\"/>"))
        assertTrue(withHint.contains("w:afterLines=\"150\"")); assertTrue(withHint.contains("w:after=\"360\"")); assertTrue(withHint.contains("w:line=\"360\""))
        val noHint = entry(result.layout.copy(hangingIndent = null, entrySpacing = null))
        assertFalse(noHint.contains("<w:ind")); assertFalse(noHint.contains("w:afterLines")); assertTrue(noHint.contains("w:after=\"0\""))
        assertFalse(entry(result.layout.copy(hangingIndent = false)).contains("<w:ind"))
        assertFalse(entry(result.layout.copy(entrySpacing = 0.0)).contains("w:afterLines"))
    }
    @Test fun deleteRange() {
        val project = DocxProject(makeDocx()); val start = second.indexOf("enough")
        project.deleteRange(1, start, start + 6, tidy = false)
        assertEquals(second.removeRange(start, start + 6), project.blockText(1))
        assertEquals(6 to 0, project.editSummary())
    }
    @Test fun undoRestoresOriginalText() {
        val project = DocxProject(makeDocx())
        project.insertCitation(0, first.length, first.length, listOf(item)); project.deleteRange(1, 0, 6, false)
        assertTrue(project.undo()); assertEquals(second, project.blockText(1))
        assertTrue(project.undo()); assertEquals(first, project.blockText(0)); assertTrue(project.presentKeys().isEmpty()); assertTrue(project.citations().isEmpty())
        assertFalse(project.undo())
    }
    @Test fun exportWritesNewFileAndLeavesSourceAlone() {
        val source = makeDocx(); val before = source.readBytes(); val project = DocxProject(source)
        project.insertCitation(0, first.length, first.length, listOf(item)); project.export(output())
        assertArrayEquals(before, source.readBytes())
        val exported = DocxProject(output())
        assertEquals("$first (Lovelace, 2020)", exported.blockText(0)); assertEquals(second, exported.blockText(1))
        val texts = (0 until exported.blockCount).map { exported.blockText(it) }
        assertTrue(texts.contains("References"))
        assertTrue(texts.any { it.contains("Lovelace, A. (2020)") && it.contains("Journal of Testing") })
    }
    @Test fun exportRefusesToOverwriteSource() {
        val source = makeDocx(); val before = source.readBytes()
        try { DocxProject(source).export(source); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun tableBlockIsReadableButNotEditable() {
        val source = makeDocx("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Alpha</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Beta</w:t></w:r></w:p></w:tc></w:tr></w:tbl>")
        val project = DocxProject(source)
        assertEquals("table", project.blockKind(0)); assertEquals("Alpha | Beta", project.blockText(0))
        try { project.deleteRange(0, 0, 1); fail("Expected rejection") } catch (_: NotEditable) { }
    }
    @Test fun emptyParagraphSupportsInsertion() {
        val project = DocxProject(makeDocx("<w:p></w:p>"))
        assertEquals("", project.blockText(0))
        project.insertCitation(0, 0, 0, listOf(item)); assertEquals("(Lovelace, 2020)", project.blockText(0))
    }
    @Test fun textSplitAcrossRunsCanBeEdited() {
        val project = DocxProject(makeDocx("<w:p><w:r><w:t>Split </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>across </w:t></w:r><w:r><w:t>runs.</w:t></w:r></w:p>"))
        assertEquals("Split across runs.", project.blockText(0))
        project.deleteRange(0, 3, 10, false); assertEquals("Splss runs.", project.blockText(0))
        assertTrue(project.undo()); assertEquals("Split across runs.", project.blockText(0))
    }
    @Test fun specialCharactersAndUnicodeSurviveInsertionCitationAndExport() {
        val project = DocxProject(makeDocx("<w:p><w:r><w:t>Start</w:t></w:r></w:p>"))
        val inserted = " & < > \" नमस्ते ગુજરાતી"
        project.replaceText(0, 5, 5, inserted)
        val unicodeItem = item.copy(key = "UTF123", authors = listOf(CitationAuthor("आशा", "પટેલ & < > \"")))
        val length = project.blockText(0).length
        project.insertCitation(0, length, length, listOf(unicodeItem))
        val expected = "Start$inserted (પટેલ & < > ,\" 2020)"
        assertEquals(expected, project.blockText(0))
        project.export(output())
        val reopened = DocxProject(output())
        assertEquals(expected, reopened.blockText(0))
        assertTrue((0 until reopened.blockCount).map { reopened.blockText(it) }.any { it.contains("પટેલ & < > \"") })
    }
    @Test fun exportPreservesEveryOtherEntryAndOrder() {
        val source = makeDocx(); val project = DocxProject(source)
        project.insertCitation(0, first.length, first.length, listOf(item)); project.export(output())
        val original = zipContent(source); val exported = zipContent(output())
        assertEquals(original.keys.toList(), exported.keys.toList())
        original.filterKeys { it != "word/document.xml" }.forEach { (name, data) -> assertArrayEquals(name, data, exported[name]) }
        assertFalse(original.getValue("word/document.xml").contentEquals(exported.getValue("word/document.xml")))
    }
    @Test fun matchesPythonGoldenFixture() {
        val source = File(temp.root, "parity.docx"); source.writeBytes(resource("parity.docx"))
        val golden = resource("parity.golden").toString(Charsets.UTF_8).trimEnd().lines().associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        val project = DocxProject(source)
        assertEquals(golden["blocks"], (0 until project.blockCount).joinToString(",") { "$it:${project.blockKind(it)}" })
        for (index in 0 until project.blockCount) assertEquals(golden["text$index"], project.blockText(index))
        val pos = project.blockText(1).length
        project.insertCitation(1, pos, pos, listOf(item)); assertEquals(golden["citation"], project.blockText(1))
        project.export(output()); val exported = DocxProject(output())
        val reference = (0 until exported.blockCount).map { exported.blockText(it) }.first { it.contains("Lovelace, A. (2020)") }
        assertEquals(golden["reference"], reference)
    }
}
