package org.blaykie.nativeapp.render

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.docx.DocxProject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentRenderReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    @Test fun resolvesParagraphAndCharacterFormattingAndHeadingLevel() {
        val styles = """<w:styles $ns>
            <w:style w:type="paragraph" w:styleId="Heading2"><w:pPr><w:outlineLvl w:val="1"/><w:jc w:val="center"/>
            <w:spacing w:before="160" w:after="80" w:line="300" w:lineRule="auto"/></w:pPr>
            <w:rPr><w:b/><w:rFonts w:ascii="serif"/><w:sz w:val="44"/></w:rPr></w:style>
            <w:style w:type="character" w:styleId="Emphasis"><w:rPr><w:i/></w:rPr></w:style>
            </w:styles>"""
        val body = """<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
            <w:r><w:t>Heading</w:t></w:r><w:r><w:rPr><w:rStyle w:val="Emphasis"/><w:b w:val="0"/>
            <w:u w:val="single"/><w:color w:val="123456"/></w:rPr><w:t> &amp; emphasis</w:t></w:r></w:p>
            <w:p><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr><w:r><w:t>Reference</w:t></w:r></w:p>"""
        val (heading, reference) = render(body, styles).blocks.filterIsInstance<RenderParagraph>()
        assertEquals(2, heading.headingLevel)
        assertEquals(RenderAlignment.Center, heading.alignment)
        assertEquals(listOf(160, 80, 300), listOf(heading.beforeTwips, heading.afterTwips, heading.lineSpacing))
        assertEquals(RunFormat(bold = true, font = "serif", sizePoints = 22f), heading.runs.first().format)
        assertEquals(RenderRun(" & emphasis", RunFormat(false, true, true, "serif", 22f, 0x123456)), heading.runs.last())
        assertEquals(720, reference.leftTwips)
        assertEquals(360, reference.hangingTwips)
    }

    @Test fun readsBulletAndNumberedItemsAndSkipsUnsupportedElements() {
        val numbering = """<w:numbering $ns>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/>
            <w:pPr><w:ind w:left="480" w:hanging="240"/></w:pPr></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="2"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1)"/>
            <w:start w:val="3"/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="2"/></w:num>
            </w:numbering>"""
        fun item(id: Int, text: String) = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="$id"/>
            </w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val hindi = "हिन्दी में क्षत्रिय और प्रज्ञा"
        val gujarati = "ગુજરાતીમાં જ્ઞાન અને શ્રદ્ધા"
        val model = render(item(1, hindi) + "<w:unknown><w:p/></w:unknown>" + item(2, gujarati) +
            "<w:tbl><w:tr/></w:tbl>" + item(2, "Next") + "<w:p><w:r><w:drawing/></w:r><w:unknown/></w:p>", numbering = numbering)
        assertEquals(5, model.blocks.size)
        val (bullet, first, next) = model.blocks.filterIsInstance<RenderParagraph>()
        assertEquals(hindi, bullet.runs.single().text)
        assertEquals(gujarati, first.runs.single().text)
        assertEquals(RenderListItem(RenderListKind.Bullet, 0, "•"), bullet.list)
        assertEquals(240, bullet.hangingTwips)
        assertEquals(RenderListItem(RenderListKind.Numbered, 0, "3)"), first.list)
        assertEquals("4)", next.list?.label)
        assertTrue(model.blocks[2] is RenderTable)
        assertEquals(RenderImage(null, null), model.blocks[4])
    }

    @Test fun readsSimpleTableAndKeepsNestedCellParagraphFormatting() {
        val model = render("""<w:tbl><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="3200"/></w:tblGrid><w:tr>
            <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
            <w:tc><w:sdt><w:sdtContent><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Nested</w:t></w:r></w:p>
            </w:sdtContent></w:sdt></w:tc></w:tr></w:tbl>""")
        val table = model.blocks.single() as RenderTable
        assertEquals(1, table.rowCount)
        assertEquals(listOf(2000, 3200), table.columnWidthsTwips)
        assertEquals(listOf(0, 1), table.cells.map { it.column })
        assertEquals("A", (table.cells.first().blocks.single() as RenderParagraph).runs.single().text)
        val nested = (table.cells.last().blocks.single() as RenderParagraph).runs.single()
        assertEquals("Nested", nested.text)
        assertEquals(true, nested.format.bold)
    }

    @Test fun joinsHorizontalAndVerticalMergesAndHandlesOrphanContinuations() {
        val model = render("""<w:tbl><w:tr>
            <w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Merged</w:t></w:r></w:p></w:tc>
            <w:tc><w:p/></w:tc></w:tr><w:tr>
            <w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge/></w:tcPr><w:p/></w:tc>
            <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p><w:r><w:t>Orphan</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""")
        val table = model.blocks.single() as RenderTable
        assertEquals(2, table.rowCount)
        assertEquals(3, table.columnWidthsTwips.size)
        assertEquals(listOf(2, 1, 1), table.cells.map { it.rowSpan })
        assertEquals(listOf(2, 1, 1), table.cells.map { it.columnSpan })
        assertEquals(listOf(0, 2, 2), table.cells.map { it.column })
        assertEquals(2, table.bands().single().rows)
        assertEquals("Orphan", (table.cells.last().blocks.single() as RenderParagraph).runs.single().text)
    }

    @Test fun resolvesEmbeddedImagesAndUsesSafeFallbackForMissingOrExternalRelationships() {
        val drawing = """<w:drawing xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <a:blip r:embed="rImage"/><a:blip r:embed="rMissing"/><a:blip r:embed="rExternal"/></w:drawing>"""
        val xml = document("<w:p><w:r><w:t>Before</w:t>$drawing<w:t>After</w:t></w:r></w:p>")
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image%201.png"/>
            <Relationship Id="rExternal" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="https://example.invalid/image.png" TargetMode="External"/>
            </Relationships>"""
        val source = docx(xml, extra = mapOf("word/_rels/document.xml.rels" to rels, "word/media/image 1.png" to "placeholder test bytes"))
        val blocks = readDocumentRenderModel(source, xml).blocks
        assertEquals("Before", (blocks.first() as RenderParagraph).runs.single().text)
        assertEquals("After", (blocks.last() as RenderParagraph).runs.single().text)
        assertEquals(listOf("word/media/image 1.png", null, null), blocks.filterIsInstance<RenderImage>().map { it.entryName })
    }

    @Test fun rendersLiveCitationsAndExistingBibliographyWithoutWritingThePackage() {
        val body = "<w:p><w:r><w:t>Paragraph</w:t></w:r></w:p>" +
            """<w:bookmarkStart w:id="1" w:name="BLK_BIBLIOGRAPHY"/><w:p><w:r><w:t>References</w:t></w:r></w:p><w:bookmarkEnd w:id="1"/>"""
        val source = docx(document(body))
        val before = source.readBytes()
        val project = DocxProject(source)
        project.insertCitation(0, 9, 9, listOf(CitationItem("ABCD1234", "Sample", listOf(CitationAuthor("Ada", "Lovelace")), "2020")))
        val paragraphs = project.renderInput().read().blocks.filterIsInstance<RenderParagraph>()
        assertEquals("Paragraph (Lovelace, 2020)", paragraphs[0].runs.joinToString("") { it.text })
        assertEquals("References", paragraphs[1].runs.single().text)
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun insertedCitationTargetsItsParagraphAndRemovalUpdatesBibliography() {
        val source = docx(document("<w:p><w:r><w:t>First</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>"))
        val project = DocxProject(source)
        val options = org.blaykie.nativeapp.docx.BibliographyOptions(enabled = true, heading = "Sources")
        fun footer() = org.blaykie.nativeapp.docx.readBibliographyOnly(project.buildXml(options = options)).bibliography
        val key = listOf(CitationItem("ABCD1234", "Sample", listOf(CitationAuthor("Ada", "Lovelace")), "2020"))
        assertTrue(footer().isEmpty())
        val number = project.insertCitation(1, 6, 6, key)
        val model = project.renderInput().read()
        assertEquals(listOf(0, 1), model.blockIndices)
        assertTrue((model.blocks[0] as RenderParagraph).runs.none { it.citationNumber != null })
        val updated = project.renderInput(1).read().blocks.single() as RenderParagraph
        assertEquals("(Lovelace, 2020)", updated.runs.filter { it.citationNumber == number }.joinToString("") { it.text })
        val insertedFooter = footer()
        assertEquals("Sources", insertedFooter.first().runs.joinToString("") { it.text })
        assertEquals(2, insertedFooter.size)
        project.removeCitation(1, number)
        assertEquals("Second paragraph", project.blockText(1))
        assertTrue(footer().isEmpty())
        assertTrue(project.undo())
        assertEquals(insertedFooter, footer())
    }

    @Test fun rejectsDtdAndExcessiveDepthInPackageParts() {
        assertThrows(IllegalArgumentException::class.java) {
            render("", styles = "<!DOCTYPE w:styles [<!ENTITY x SYSTEM 'file:///ignored'>]><w:styles $ns/>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            render("<w:unknown>".repeat(130) + "</w:unknown>".repeat(130))
        }
    }

    private fun document(body: String) = "<w:document $ns><w:body>$body</w:body></w:document>"
    private fun render(body: String, styles: String? = null, numbering: String? = null): DocumentRenderModel {
        val xml = document(body)
        return readDocumentRenderModel(docx(xml, styles, numbering), xml)
    }
    private fun docx(xml: String, styles: String? = null, numbering: String? = null, extra: Map<String, String> = emptyMap()): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, part) in (listOf("word/document.xml" to xml, "word/styles.xml" to styles, "word/numbering.xml" to numbering) + extra.toList())) {
                if (part == null) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(part.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
