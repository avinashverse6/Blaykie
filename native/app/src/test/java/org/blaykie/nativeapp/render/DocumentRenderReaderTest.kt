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

    @Test fun parsesBottomBorderAndInheritsHeadingRunAndSpacing() {
        val styles = """<w:styles $ns>
            <w:docDefaults><w:pPrDefault><w:pPr><w:spacing w:line="300" w:after="160"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:styleId="Base"><w:pPr><w:pBdr>
                <w:bottom w:val="single" w:sz="12" w:color="224466" w:space="3"/>
            </w:pBdr></w:pPr><w:rPr><w:sz w:val="48"/><w:color w:val="123456"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:basedOn w:val="Base"/></w:style>
            </w:styles>"""
        val (heading, exact, atLeast) = render("""
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>
            <w:p><w:pPr><w:spacing w:line="200" w:lineRule="exact" w:before="80"/></w:pPr></w:p>
            <w:p><w:pPr><w:spacing w:line="400" w:lineRule="atLeast"/></w:pPr></w:p>
            """, styles).blocks.filterIsInstance<RenderParagraph>()
        assertEquals(RenderBorder(12, 0x224466, 3), heading.borders.bottom)
        assertEquals(1, heading.headingLevel)
        assertEquals(24f, heading.runs.single().format.sizePoints)
        assertEquals(0x123456, heading.runs.single().format.rgb)
        assertEquals(300 to "auto", heading.lineSpacing to heading.lineRule)
        assertEquals(200 to "exact", exact.lineSpacing to exact.lineRule)
        assertEquals(400 to "atLeast", atLeast.lineSpacing to atLeast.lineRule)
        assertEquals(80 to 160, exact.beforeTwips to exact.afterTwips)
    }

    @Test fun usesFontCategoryForMissingDocumentFonts() {
        assertEquals(DocumentFontCategory.SansSerif, documentFontCategory("Open Sans"))
        assertEquals(DocumentFontCategory.Serif, documentFontCategory("Times New Roman"))
        assertEquals(DocumentFontCategory.SansSerif, documentFontCategory("Unknown Document Font"))
    }

    @Test fun onlyShowsImagePlaceholderOnFailure() {
        assertNull(imagePlaceholderText(ImageDisplay.Drawn))
        assertNull(imagePlaceholderText(ImageDisplay.Loading))
        assertEquals("[image]", imagePlaceholderText(ImageDisplay.Failed))
    }

    @Test fun treatsAnchoredPictureAndItsCompatibilityFallbackAsOneImage() {
        val xml = document("""<w:p><w:r>
            <mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
                xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:v="urn:schemas-microsoft-com:vml">
                <mc:Choice Requires="wp"><w:drawing><wp:anchor><a:blip r:embed="rImage"/></wp:anchor></w:drawing></mc:Choice>
                <mc:Fallback><w:pict><v:imagedata r:id="rMissing"/></w:pict></mc:Fallback>
            </mc:AlternateContent>
            </w:r></w:p>""")
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image.png"/>
            </Relationships>"""
        val source = docx(xml, extra = mapOf("word/_rels/document.xml.rels" to rels, "word/media/image.png" to "test bytes"))
        assertEquals(listOf(RenderImage("word/media/image.png", null)), readDocumentRenderModel(source, xml).blocks)
    }

    @Test fun reportLikeGoldRuleAndPictureFallbackStaySeparate() {
        val rule = """<w:p><w:r><w:pict><v:rect xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:o="urn:schemas-microsoft-com:office:office" o:hr="t" fillcolor="#B89B72" style="height:2.25pt"/>
            </w:pict></w:r></w:p>"""
        val fallback = """<w:r><w:pict><v:imagedata xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="rMissing"/></w:pict></w:r>"""
        val picture = """<w:r><w:drawing><wp:anchor xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
            <a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="rPicture"/>
            </wp:anchor></w:drawing></w:r>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rPicture" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/report.png"/>
            </Relationships>"""
        val extra = mapOf("word/_rels/document.xml.rels" to rels, "word/media/report.png" to "fixture media")
        val sdt = "<w:sdt><w:sdtPr><w:picture/></w:sdtPr><w:sdtContent>"
        val title = "<w:p><w:r><w:t>REPORT TITLE</w:t></w:r></w:p>"
        for (body in listOf("<w:p>$fallback$picture</w:p>", "$sdt<w:p>$fallback</w:p><w:p>$picture</w:p></w:sdtContent></w:sdt>")) {
            val xml = document(title + rule + body + "<w:p>$fallback</w:p>")
            val blocks = readDocumentRenderModel(docx(xml, extra = extra), xml).blocks
            assertEquals(RenderHorizontalRule(0xB89B72, 2.25f), blocks[1])
            val (failedSibling, drawnPicture, unrelatedFailure) = blocks.filterIsInstance<RenderImage>()
            assertEquals("word/media/report.png", drawnPicture.entryName)
            fun canDecode(entry: String) = entry == drawnPicture.entryName
            assertNull(imagePlaceholderText(failedImageDisplay(failedSibling, ::canDecode)))
            assertEquals("[image]", imagePlaceholderText(failedImageDisplay(failedSibling) { false }))
            assertEquals("[image]", imagePlaceholderText(failedImageDisplay(unrelatedFailure, ::canDecode)))
        }
    }

    @Test fun parsesHorizontalDrawingMlLineWithExplicitStroke() {
        val blocks = render("""<w:p><w:r><w:drawing xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
            <a:spPr><a:xfrm><a:ext cx="1270000" cy="0"/></a:xfrm><a:prstGeom prst="line"/>
            <a:ln w="25400"><a:solidFill><a:srgbClr val="B89B72"/></a:solidFill></a:ln></a:spPr>
            </w:drawing></w:r></w:p>""").blocks
        assertEquals(listOf(RenderHorizontalRule(0xB89B72, 2f)), blocks)
    }

    @Test fun thinInlinePngKeepsItsExtentAndCanBeSampledWithoutPlaceholder() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/thin-inline.png")).use { it.readBytes() }
        val xml = document("""<w:p><w:r><w:drawing>
            <wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
                <wp:extent cx="5943600" cy="38100"/><wp:docPr id="2" descr="horizontal line"/>
                <a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="rId2"/>
            </wp:inline></w:drawing></w:r></w:p>""")
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image2.png"/>
            </Relationships>"""
        val source = docx(xml, extra = mapOf("word/_rels/document.xml.rels" to rels), binary = mapOf("word/media/image2.png" to bytes))
        val image = readDocumentRenderModel(source, xml).blocks.single() as RenderImage
        assertEquals("horizontal line", image.description)
        assertEquals("word/media/image2.png", image.entryName)
        val extent = checkNotNull(image.inlineExtent)
        assertEquals(InlineImageExtent(5943600, 38100), extent)
        assertEquals(2f, extent.displayHeightDp(312f), .001f)
        assertEquals(1f, extent.displayHeightDp(100f), .001f)
        readRenderPackage(source) { zip ->
            zip.getInputStream(zip.getEntry(image.entryName)).use { input ->
                // PNG IHDR dimensions are big-endian; Android's decoder reads these same bounds.
                val header = java.nio.ByteBuffer.wrap(input.readBytes())
                val width = header.getInt(16)
                val height = header.getInt(20)
                assertEquals(1200, width)
                assertEquals(8, height)
                val sample = checkNotNull(imageSampleSize(width, height, 24))
                assertEquals(8, sample)
                assertTrue(width / sample >= 1 && height / sample >= 1)
            }
        }
        assertNull(imagePlaceholderText(ImageDisplay.Drawn))
    }

    @Test fun readsPaperSizeMarginsAndOrientationFromSectionProperties() {
        assertEquals(PageSetup(), render("<w:p/>").pageSetup)
        val a4 = render("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
            <w:pgMar w:top="720" w:right="1080" w:bottom="1260" w:left="1600"/></w:sectPr>""").pageSetup
        assertEquals(PageSetup(11906, 16838, false, 720, 1080, 1260, 1600), a4)
        val letter = render("""<w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr>""").pageSetup
        assertEquals(PageSetup(12240, 15840), letter)
        val landscape = render("""<w:sectPr><w:pgSz w:w="15840" w:h="12240" w:orient="landscape"/>
            <w:pgMar w:left="1000" w:right="1100"/></w:sectPr>""").pageSetup
        assertEquals(PageSetup(15840, 12240, true, rightTwips = 1100, leftTwips = 1000), landscape)
    }

    @Test fun printHonorsPageBreakBeforeAndSplitsTextAtExplicitBreaks() {
        val styles = """<w:styles $ns><w:style w:type="paragraph" w:styleId="NewPage">
            <w:pPr><w:pageBreakBefore/></w:pPr></w:style></w:styles>"""
        val paragraph = render("""<w:p><w:pPr><w:pStyle w:val="NewPage"/></w:pPr>
            <w:r><w:t>Before</w:t><w:br w:type="page"/><w:t>After</w:t></w:r></w:p>""", styles)
            .blocks.single() as RenderParagraph
        assertTrue(paragraph.pageBreakBefore)
        assertEquals("Before\nAfter", paragraph.runs.single().text)
        assertEquals(listOf("Before", "After"), paragraph.printFragments().map { it.runs.single().text })
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

    @Test fun printRowsUseTheSourceParagraphAtFitWidthAndDoubleZoom() {
        val model = render("""<w:p><w:r><w:t>First</w:t></w:r></w:p>
            <w:tbl><w:tr/></w:tbl><w:p><w:r><w:t>Target</w:t></w:r></w:p>
            <w:sdt><w:sdtContent><w:p><w:r><w:t>Read only</w:t></w:r></w:p></w:sdtContent></w:sdt>""")
        val rows = model.pageItems().filterIsInstance<MobilePageItem.Block>().mapIndexed { index, item ->
            PrintRow(item.blockIndex, index * 24f, item)
        }
        assertEquals(listOf(0, 2, null), rows.map { it.editorBlock })
        for (zoom in listOf(1f, 2f)) {
            val pixel = ((72f + 24f + 8f) * zoom).toInt()
            assertEquals(2, printRowAtOffset(rows, pixel, 72f, zoom)?.editorBlock)
        }
    }

    @Test fun printCitationAndLiveBibliographyFollowInsertRemoveAndUndo() = kotlinx.coroutines.runBlocking {
        val source = docx(document("<w:p><w:r><w:t>First</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Second</w:t></w:r></w:p>"))
        val project = DocxProject(source)
        val options = org.blaykie.nativeapp.docx.BibliographyOptions(enabled = true, heading = "Sources")
        suspend fun footer(): List<PrintRow> {
            val ready = org.blaykie.nativeapp.export.PreviewReferences.Ready(project, emptySet(), false)
            val preview = org.blaykie.nativeapp.export.renderBibliographyPreview(ready, bibliographyOnly = true) { copy, allowMissing ->
                copy.buildXml(allowMissing = allowMissing, options = options)
            }
            return printBibliographyRows(1, preview.end.bibliography)
        }
        fun printRows() = project.renderInput().read().pageItems().filterIsInstance<MobilePageItem.Block>()
            .flatMap(::printParagraphItems).map { PrintRow(it.blockIndex, 0f, it) }
        fun cites(row: PrintRow) = ((row.item as MobilePageItem.Block).content as RenderParagraph).runs.mapNotNull { it.citationNumber }
        assertTrue(footer().isEmpty())
        val number = project.insertCitation(1, 6, 6, listOf(CitationItem("ABCD1234", "Sample",
            listOf(CitationAuthor("Ada", "Lovelace")), "2020")))
        val rows = printRows()
        assertTrue(cites(rows.first()).isEmpty())
        assertEquals(1, rows.last().editorBlock)
        assertEquals(listOf(number), cites(rows.last()))
        val withCitation = footer()
        assertEquals(2, withCitation.size)
        assertTrue(withCitation.first().title)
        project.removeCitation(1, number)
        assertTrue(cites(printRows().last()).isEmpty())
        assertTrue(footer().isEmpty())
        assertTrue(project.undo())
        assertEquals(listOf(number), cites(printRows().last()))
        assertEquals(withCitation, footer())
    }

    @Test fun removalReplaysAndUndoRestoresCitationWithoutChangingDocx() {
        val source = docx(document("<w:p><w:r><w:t>Original text</w:t></w:r></w:p>"))
        val bytes = source.readBytes()
        val store = org.blaykie.nativeapp.session.SessionStore(temp.newFolder(), source)
        val opened = store.attach("content://test", "Test.docx")
        val key = listOf(CitationItem("ABCD1234", "Sample", listOf(CitationAuthor("Ada", "Lovelace")), "2020"))
        val insert = org.blaykie.nativeapp.session.EditOperation(0, type = "citation", position = 8, items = key)
        val number = opened.project.insertCitation(0, 8, 8, key)
        val remove = org.blaykie.nativeapp.session.EditOperation(0, type = "removeCitation", position = number)
        val session = opened.session.copy(edits = listOf(insert, remove))
        store.save(session)
        val restored = (store.read() as org.blaykie.nativeapp.session.SessionRead.Ready).project
        assertEquals("Original text", restored.blockText(0))
        val undone = store.undo(session) as org.blaykie.nativeapp.session.UndoResult.Success
        assertTrue(undone.project.blockText(0).contains("Lovelace"))
        val copy = undone.project.copyForBibliographyPreview()
        assertEquals(undone.project.buildXml(), copy.buildXml())
        copy.removeCitation(0, number)
        assertEquals("Original text", copy.blockText(0))
        assertTrue(undone.project.blockText(0).contains("Lovelace"))
        assertArrayEquals(bytes, source.readBytes())
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
    private fun docx(xml: String, styles: String? = null, numbering: String? = null, extra: Map<String, String> = emptyMap(),
                     binary: Map<String, ByteArray> = emptyMap()): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, part) in (listOf("word/document.xml" to xml, "word/styles.xml" to styles, "word/numbering.xml" to numbering) + extra.toList())) {
                if (part == null) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(part.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for ((name, bytes) in binary) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
