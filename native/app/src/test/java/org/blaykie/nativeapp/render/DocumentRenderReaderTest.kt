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
        val (bullet, first, next, image) = model.blocks.filterIsInstance<RenderParagraph>()
        assertEquals(hindi, bullet.runs.single().text)
        assertEquals(gujarati, first.runs.single().text)
        assertEquals(RenderListItem(RenderListKind.Bullet, 0, "•"), bullet.list)
        assertEquals(240, bullet.hangingTwips)
        assertEquals(RenderListItem(RenderListKind.Numbered, 0, "3)"), first.list)
        assertEquals("4)", next.list?.label)
        assertEquals(RenderPlaceholder.Table, model.blocks[2])
        assertEquals("[image]", image.runs.single().text)
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
    private fun docx(xml: String, styles: String? = null, numbering: String? = null): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, part) in listOf("document" to xml, "styles" to styles, "numbering" to numbering)) {
                if (part == null) continue
                zip.putNextEntry(ZipEntry("word/$name.xml"))
                zip.write(part.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
