package org.blaykie.nativeapp.render

internal data class DocumentRenderModel(val blocks: List<RenderBlock>)
internal sealed interface RenderBlock
internal enum class RenderPlaceholder(val label: String) : RenderBlock { Table("[table]") }
internal enum class RenderAlignment { Start, End, Left, Right, Center, Justify }
internal enum class RenderListKind { Bullet, Numbered }
internal data class RenderListItem(val kind: RenderListKind, val level: Int, val label: String)
internal data class RenderRun(val text: String, val format: RunFormat)
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
