package org.blaykie.nativeapp.render

internal data class DocumentRenderModel(val blocks: List<RenderBlock>, val source: java.io.File, val blockIndices: List<Int>,
                                        val pageSetup: PageSetup = PageSetup())
internal sealed interface RenderBlock
internal enum class RenderAlignment { Start, End, Left, Right, Center, Justify }
internal enum class RenderListKind { Bullet, Numbered }
internal data class RenderListItem(val kind: RenderListKind, val level: Int, val label: String)
internal data class RenderRun(val text: String, val format: RunFormat, val citationNumber: Int? = null)
internal data class RunFormat(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val font: String? = null, val sizePoints: Float? = null, val rgb: Int? = null,
)
internal data class RenderBorder(val widthEighthPoints: Int, val rgb: Int?, val spacePoints: Int)
internal data class RenderBorders(val top: RenderBorder? = null, val bottom: RenderBorder? = null,
                                  val left: RenderBorder? = null, val right: RenderBorder? = null)

internal data class RenderParagraph(
    val runs: List<RenderRun>,
    val alignment: RenderAlignment = RenderAlignment.Start,
    val beforeTwips: Int = 0, val afterTwips: Int = 0,
    val lineSpacing: Int? = null, val lineRule: String? = null,
    val leftTwips: Int = 0, val hangingTwips: Int = 0, val firstLineTwips: Int = 0,
    val headingLevel: Int? = null, val list: RenderListItem? = null,
    val borders: RenderBorders = RenderBorders(),
    val pageBreakBefore: Boolean = false, val pageBreaks: List<Int> = emptyList(),
) : RenderBlock

internal data class RenderImage(val entryName: String?, val description: String?, val fallbackEntries: List<String> = emptyList(),
                                val siblingEntries: List<String> = emptyList(), val inlineExtent: InlineImageExtent? = null) : RenderBlock {
    fun entries(): List<String> = listOfNotNull(entryName) + fallbackEntries
}
internal data class InlineImageExtent(val widthEmu: Long, val heightEmu: Long) {
    fun displayHeightDp(widthDp: Float): Float = (widthDp * (heightEmu.toDouble() / widthEmu)).toFloat().coerceAtLeast(1f)
}

internal data class RenderHorizontalRule(val rgb: Int?, val thicknessPoints: Float) : RenderBlock

/** Preserve each picture's position; its siblings are only used to decide whether to show a failure label. */
internal fun List<RenderBlock>.groupImageFailures(): List<RenderBlock> {
    val pictures = filterIsInstance<RenderImage>()
    if (pictures.size < 2) return this
    val entries = pictures.flatMap { it.entries() }.distinct()
    if (entries.isEmpty()) return this
    return map { if (it is RenderImage) it.copy(siblingEntries = entries) else it }
}
internal data class RenderTable(val columnWidthsTwips: List<Int>, val rowCount: Int, val cells: List<RenderCell>) : RenderBlock
internal data class RenderCell(
    val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int, val blocks: List<RenderBlock>,
)
