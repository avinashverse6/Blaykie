package org.blaykie.nativeapp.docx

import kotlinx.serialization.Serializable

@Serializable
data class BibliographyOptions(val enabled: Boolean = true, val heading: String = "References")

internal data class ReferenceRun(val text: String, val italic: Boolean, val bold: Boolean = false,
    val underline: Boolean = false, val smallCaps: Boolean = false)
internal data class ReferenceList(val entries: List<List<ReferenceRun>>, val hangingIndent: Boolean,
    val entrySpacing: Double? = null) {
    fun section(options: BibliographyOptions): BibliographySection? =
        if (!options.enabled || entries.isEmpty()) null
        else BibliographySection(options.heading.replace(Regex("[\\x00-\\x1F\\uFFFE\\uFFFF]+"), " ").trim().ifEmpty { "References" }, this)
}
internal data class BibliographySection(val heading: String, val references: ReferenceList)

internal fun apaReferenceList(items: List<CitationItem>): ReferenceList = ReferenceList(
    apaBibliographyEntries(items).map { entry -> entry.map { (text, italic) -> ReferenceRun(text, italic) } }, true,
)

internal data class BibliographyBlock(val tag: String, val gap: String, val xml: String)

private val bookmarkName = Regex("""\bw:name="BLK_BIBLIOGRAPHY"""")
private val bookmarkId = Regex("""\bw:id="(\d+)"""")
private val nonReferenceContent = Regex("""</?(?:w:)?(?:fldSimple|fldChar|instrText|sdt|hyperlink|bookmarkStart|bookmarkEnd|drawing|object|sectPr)\b""")

/** Replace only the app's paired top-level markers, preserving unrelated body content. */
internal fun replaceBibliography(blocks: List<BibliographyBlock>, bibliography: String): String = buildString {
    val insertion = if (blocks.lastOrNull()?.tag == "w:sectPr") blocks.lastIndex else blocks.size
    var index = 0
    while (index < blocks.size) {
        if (index == insertion) append(bibliography)
        val block = blocks[index++]
        append(block.gap)
        if (block.tag != "w:bookmarkStart" || !bookmarkName.containsMatchIn(block.xml)) {
            append(block.xml)
            continue
        }
        val id = requireNotNull(bookmarkId.find(block.xml)?.groupValues?.get(1))
        while (index < insertion && blocks[index].tag == "w:p") {
            require(!nonReferenceContent.containsMatchIn(blocks[index].xml)) { "Cannot safely replace the existing bibliography." }
            append(blocks[index++].gap)
        }
        require(index < insertion && blocks[index].tag == "w:bookmarkEnd" &&
            bookmarkId.find(blocks[index].xml)?.groupValues?.get(1) == id) { "Cannot safely replace the existing bibliography." }
        append(blocks[index++].gap)
    }
    if (insertion == blocks.size) append(bibliography)
}
