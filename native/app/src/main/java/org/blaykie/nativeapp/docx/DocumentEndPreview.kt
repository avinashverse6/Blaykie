package org.blaykie.nativeapp.docx

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal data class EndPreviewParagraph(val runs: List<ReferenceRun>, val hangingTwips: Int)
internal data class DocumentEndPreview(val paragraphs: List<EndPreviewParagraph>, val bibliography: List<EndPreviewParagraph>)

internal fun DocxProject.previewDocumentEnd(options: BibliographyOptions): DocumentEndPreview =
    readDocumentEnd(buildXml(options = options))

/** Read a display-only view of the actual document XML that the export path produces. */
internal fun readDocumentEnd(xml: String): DocumentEndPreview {
    // DOCX parts don't need DTDs; never allow document-provided entity definitions in the preview.
    require(!xml.contains("<!DOCTYPE"))
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    val document = builder.parse(InputSource(StringReader(xml)))
    val body = document.getElementsByTagName("w:body").item(0) as Element
    val blocks = body.elements().toList()
    val start = blocks.indexOfLast { it.tagName == "w:bookmarkStart" && it.getAttribute("w:name") == "BLK_BIBLIOGRAPHY" }
    val end = if (start < 0) -1 else (start + 1 until blocks.size).firstOrNull {
        blocks[it].tagName == "w:bookmarkEnd" && blocks[it].getAttribute("w:id") == blocks[start].getAttribute("w:id")
    } ?: -1
    val before = if (end < 0) blocks else blocks.take(start)
    return DocumentEndPreview(
        before.filter { it.tagName == "w:p" }.takeLast(3).map(::previewParagraph),
        if (end < 0) emptyList() else blocks.subList(start + 1, end).filter { it.tagName == "w:p" }.map(::previewParagraph),
    )
}

private fun Element.elements(): Sequence<Element> = (0 until childNodes.length).asSequence()
    .mapNotNull { childNodes.item(it) as? Element }

private fun previewParagraph(paragraph: Element): EndPreviewParagraph {
    val properties = paragraph.elements().firstOrNull { it.tagName == "w:pPr" }
    val indent = properties?.elements()?.firstOrNull { it.tagName == "w:ind" }
        ?.getAttribute("w:hanging")?.toIntOrNull() ?: 0
    val runs = paragraph.getElementsByTagName("w:r")
    return EndPreviewParagraph((0 until runs.length).mapNotNull { index ->
        val run = runs.item(index) as Element
        val text = run.elements().joinToString("") {
            when (it.tagName) {
                "w:t" -> it.textContent
                "w:tab" -> "\t"
                "w:br", "w:cr" -> "\n"
                else -> ""
            }
        }
        if (text.isEmpty()) return@mapNotNull null
        val format = run.elements().firstOrNull { it.tagName == "w:rPr" }
        fun has(tag: String) = format?.elements()?.any {
            it.tagName == tag && it.getAttribute("w:val") !in setOf("0", "false", "off", "none")
        } == true
        ReferenceRun(text, has("w:i"), has("w:b"), has("w:u"), has("w:smallCaps"))
    }, indent.coerceAtLeast(0))
}
