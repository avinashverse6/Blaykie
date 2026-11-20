package org.blaykie.nativeapp.render

internal data class DocumentRenderModel(val blocks: List<RenderBlock>, val source: java.io.File, val blockIndices: List<Int>)
internal sealed interface RenderBlock
internal enum class RenderAlignment { Start, End, Left, Right, Center, Justify }
internal enum class RenderListKind { Bullet, Numbered }
internal data class RenderListItem(val kind: RenderListKind, val level: Int, val label: String)
internal data class RenderRun(val text: String, val format: RunFormat, val citationNumber: Int? = null)
internal data class RunFormat(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val font: String? = null, val sizePoints: Float? = null, val rgb: Int? = null,
)
internal data class RenderParagraph(
    val runs: List<RenderRun>,
    val alignment: RenderAlignment = RenderAlignment.Start,
    val beforeTwips: Int = 0, val afterTwips: Int = 0,
    val lineSpacing: Int? = null, val lineRule: String? = null,
    val leftTwips: Int = 0, val hangingTwips: Int = 0, val firstLineTwips: Int = 0,
    val headingLevel: Int? = null, val list: RenderListItem? = null,
) : RenderBlock

internal data class RenderImage(val entryName: String?, val description: String?) : RenderBlock
internal data class RenderTable(val columnWidthsTwips: List<Int>, val rowCount: Int, val cells: List<RenderCell>) : RenderBlock
internal data class RenderCell(
    val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int, val blocks: List<RenderBlock>,
)
