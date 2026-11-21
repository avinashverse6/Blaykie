package org.blaykie.nativeapp.render

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.BibliographyStatus
import org.blaykie.nativeapp.BibliographyViewState
import org.blaykie.nativeapp.PreviewParagraph
import org.blaykie.nativeapp.docx.EndPreviewParagraph
import kotlin.math.roundToInt

internal data class PrintPage(val rows: List<PrintRow>)
private data class PrintPagination(val revision: Int, val pages: List<PrintPage>)
internal data class PrintRow(val blockIndex: Int, val top: Float, val item: MobilePageItem? = null,
                             val reference: EndPreviewParagraph? = null, val title: Boolean = false, val label: String? = null) {
    // Compose hit-tests the scaled row, so the source target does not depend on zoom or pan.
    val editorBlock: Int? get() = (item as? MobilePageItem.Block)?.takeIf { it.editable }?.blockIndex
    fun printOffsetPx(topMargin: Float, pixelsPerPoint: Float): Int = ((topMargin + top) * pixelsPerPoint).roundToInt()
}

internal fun printRowAtOffset(rows: List<PrintRow>, offset: Int, topMargin: Float, pixelsPerPoint: Float): PrintRow? =
    rows.lastOrNull { it.printOffsetPx(topMargin, pixelsPerPoint) <= offset } ?: rows.firstOrNull()

internal fun printParagraphItems(item: MobilePageItem.Block): List<MobilePageItem.Block> =
    (item.content as? RenderParagraph)?.printFragments()?.map { item.copy(content = it) }.orEmpty()

internal fun printBibliographyRows(block: Int, entries: List<EndPreviewParagraph>): List<PrintRow> =
    entries.mapIndexed { index, entry -> PrintRow(block, 0f, reference = entry, title = index == 0) }

/** Bounded per-document cache survives editor navigation. Unchanged paragraphs are not remeasured. */
internal class PrintMeasurements {
    private data class Key(val paragraph: RenderParagraph, val width: Float, val size: Float, val fontScale: Float)
    private val heights = object : LinkedHashMap<Key, Float>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Float>): Boolean = size > 4096
    }
    @Synchronized
    fun paragraph(p: RenderParagraph, width: Float, size: Float, scale: Float, measure: () -> Float): Float =
        heights.getOrPut(Key(p, width, size, scale), measure)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun PrintDocument(document: MobilePage, state: MobileDocumentState, footer: BibliographyViewState?,
                           fonts: MutableMap<String, FontFamily?>, tableScroll: MutableMap<Int, ScrollState>,
                           onParagraph: (Int) -> Unit, onCitation: (Int, Int) -> Unit) {
    val setup = document.setup
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val darkPaper = colors.background.luminance() < .5f && !state.lightPrintPage
    val paper = if (darkPaper) colors.surface else Color.White
    val paperColors = if (darkPaper) colors.copy(background = paper, onBackground = colors.onSurface) else lightColorScheme(primary = colors.primary, primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer, background = Color.White, surface = Color.White)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val fontScale = LocalDensity.current.fontScale
    val result = footer?.content
    val renderedRevision = state.renderedRevision
    val pagination by produceState<PrintPagination?>(null, document, result, typography, measurer, fontScale, renderedRevision) {
        value = withContext(Dispatchers.Default) {
            val estimate = PrintMeasure(measurer, typography.bodyLarge.fontSize.value, fontScale, state.printMeasurements)
            val sheets = mutableListOf<PrintPage>()
            var rows = mutableListOf<PrintRow>()
            var cursor = 0f
            fun nextPage() {
                if (rows.isEmpty()) return
                sheets.add(PrintPage(rows))
                rows = mutableListOf()
                cursor = 0f
            }
            fun add(row: PrintRow, height: Float, pageBreak: Boolean = false) {
                if (pageBreak || cursor + height > setup.contentHeightPoints) nextPage()
                rows.add(row.copy(top = cursor))
                cursor += height
            }
            var previousAfter = 0
            for (item in document.items) {
                ensureActive()
                val p = (item as? MobilePageItem.Block)?.content as? RenderParagraph
                if (p == null) {
                    add(PrintRow(item.blockIndex, 0f, item), estimate.height(item, setup.contentWidthPoints))
                    previousAfter = 0
                } else for ((index, fragment) in printParagraphItems(item as MobilePageItem.Block).withIndex()) {
                    val part = fragment.content as RenderParagraph
                    val explicit = index > 0 || part.pageBreakBefore
                    val height = estimate.paragraph(part, setup.contentWidthPoints, previousAfter)
                    val newPage = explicit || cursor + height > setup.contentHeightPoints
                    add(PrintRow(item.blockIndex, 0f, fragment), if (newPage) height + minOf(previousAfter.coerceIn(0, 4800), part.beforeTwips.coerceIn(0, 4800)) / 20f else height, newPage)
                    previousAfter = part.afterTwips
                }
            }
            val end = document.items.lastOrNull()?.blockIndex ?: 0
            for (row in printBibliographyRows(end, result?.end?.bibliography.orEmpty())) {
                ensureActive()
                val height = estimate.reference(checkNotNull(row.reference), setup.contentWidthPoints)
                add(row, height, pageBreak = row.title)
            }
            result?.missingNote?.let { add(PrintRow(end, 0f, label = it), 24f) }
            result?.missingCitations?.forEach { add(PrintRow(end, 0f, label = it), 24f) }
            nextPage()
            PrintPagination(renderedRevision, sheets.ifEmpty { listOf(PrintPage(emptyList())) })
        }
    }
    val zoom = state.printZoom
    val pan = state.printPan
    val transform = rememberTransformableState { change, offset, _ ->
        state.printZoom = (state.printZoom * change).coerceIn(1f, 3f)
        if (state.printZoom > 1f) pan.dispatchRawDelta(-offset.x)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(if (darkPaper) Color(0xff111218) else Color(0xffdedee0))
        .transformable(transform, canPan = { false }, lockRotationOnZoomPan = true)) {
        val density = LocalDensity.current
        val sheetWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)
        val width = sheetWidth * zoom
        val scale = with(density) { width.toPx() } / (setup.widthTwips / 20f)
        SideEffect { state.printScale(scale) }
        val rendered = pagination
        val sheets = rendered?.pages
        if (sheets == null) {
            CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
        } else {
            LaunchedEffect(rendered, state.revision) {
                if (rendered?.revision == state.revision) state.showPrintPages(sheets, setup.topTwips / 20f)
            }
            Box(Modifier.horizontalScroll(pan, enabled = zoom > 1f)) {
                LazyColumn(Modifier.width(width + 16.dp).fillMaxHeight(), state = state.printScroll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(sheets) { pageIndex, page ->
                        CompositionLocalProvider(LocalDensity provides Density(scale, density.fontScale),
                            LocalDarkPaper provides if (darkPaper) DarkPaper(paper, paperColors.onSurface) else null,
                            LocalContentColor provides if (darkPaper) paperColors.onSurface else LocalContentColor.current) {
                            MaterialTheme(colorScheme = paperColors, typography = typography) {
                                Column(Modifier.fillMaxWidth().heightIn(min = (setup.heightTwips / 20f).dp)
                                    .shadow(3.dp, RectangleShape).background(paper)
                                    .absolutePadding(left = (setup.leftTwips / 20f).dp, right = (setup.rightTwips / 20f).dp,
                                        top = (setup.topTwips / 20f).dp, bottom = (setup.bottomTwips / 20f).dp)) {
                                    var previousAfter = 0
                                    for (row in page.rows) {
                                        val item = row.item
                                        when {
                                            item != null -> {
                                                val target = row.editorBlock
                                                DocumentBlock(document.source, item, fonts, tableScroll, previousAfter,
                                                    onParagraph = target?.let { block -> { onParagraph(block) } },
                                                    onCitation = target?.let { block -> { citation -> onCitation(block, citation) } })
                                                previousAfter = ((item as? MobilePageItem.Block)?.content as? RenderParagraph)?.afterTwips ?: 0
                                            }
                                            row.reference != null -> PreviewParagraph(row.reference, row.title)
                                            row.label != null -> Text(row.label)
                                        }
                                    }
                                    if (pageIndex == sheets.lastIndex && footer != null) {
                                        if (result == null) BibliographyStatus(footer)
                                        else if (footer.lookupAttempted) TextButton(onClick = footer.retry) { Text("Retry") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Page-break offsets point at the newline retained in the Mobile flow. Preserve the surrounding runs. */
internal fun RenderParagraph.printFragments(): List<RenderParagraph> {
    if (pageBreaks.isEmpty()) return listOf(this)
    val chunks = mutableListOf<MutableList<RenderRun>>(mutableListOf())
    var offset = 0
    var nextBreak = 0
    for (run in runs) {
        var from = 0
        while (nextBreak < pageBreaks.size && pageBreaks[nextBreak] < offset + run.text.length) {
            val cut = pageBreaks[nextBreak++] - offset
            if (cut > from) chunks.last().add(run.copy(text = run.text.substring(from, cut)))
            chunks.add(mutableListOf())
            from = cut + 1
        }
        if (from < run.text.length) chunks.last().add(run.copy(text = run.text.substring(from)))
        offset += run.text.length
    }
    return chunks.mapIndexed { index, runs -> copy(runs = runs, pageBreaks = emptyList(),
        list = list.takeIf { index == 0 }, beforeTwips = if (index == 0) beforeTwips else 0,
        afterTwips = if (index == chunks.lastIndex) afterTwips else 0) }
}

private class PrintMeasure(private val measurer: TextMeasurer, private val bodySize: Float, private val fontScale: Float, private val cache: PrintMeasurements) {
    // Layout in paper points independent of zoom. Text measurement uses Android shaping and font fallback.
    private val density = Density(1f, fontScale)
    private val fonts = mutableMapOf<String, FontFamily>()

    fun paragraph(p: RenderParagraph, width: Float, previousAfter: Int): Float {
        val measured = cache.paragraph(p, width, bodySize, fontScale) { measureParagraph(p, width) }
        return measured + (p.beforeTwips.coerceIn(0, 4800) - previousAfter.coerceIn(0, 4800)).coerceAtLeast(0) / 20f +
            p.afterTwips.coerceIn(0, 4800) / 20f + borderInset(p.borders.top) + borderInset(p.borders.bottom)
    }

    private fun measureParagraph(p: RenderParagraph, width: Float): Float {
        val size = p.defaultFontSize(bodySize)
        val line = p.lineHeightPoints(size)
        val indent = (p.leftTwips.coerceIn(0, 2400) / 20f).takeIf { it > 0 } ?: (p.list?.let { 16f + 10 * it.level } ?: 0f)
        val text = buildAnnotatedString {
            p.list?.let { append(it.label); append("\u00a0\u00a0") }
            for (run in p.runs) withStyle(SpanStyle(fontSize = (run.format.sizePoints?.coerceIn(6f, 100f) ?: size).sp,
                fontFamily = run.format.font?.let { fonts.getOrPut(it) { availableFont(it) } } ?: FontFamily.SansSerif,
                fontWeight = if (run.format.bold ?: (p.headingLevel != null)) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (run.format.italic == true) FontStyle.Italic else FontStyle.Normal)) { append(run.text) }
        }
        val style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            textIndent = TextIndent(firstLine = (indent - p.hangingTwips.coerceIn(0, 1440) / 20f +
                p.firstLineTwips.coerceIn(0, 1440) / 20f).coerceAtLeast(0f).sp, restLine = indent.sp))
        return measure(text, style, width - borderInset(p.borders.left) - borderInset(p.borders.right))
    }

    fun reference(entry: EndPreviewParagraph, width: Float): Float = measure(
        AnnotatedString(entry.runs.joinToString("") { it.text }),
        TextStyle(fontSize = bodySize.sp, lineHeight = (bodySize * 1.45f).sp,
            textIndent = TextIndent(restLine = (entry.hangingTwips / 20f).sp)), width)

    fun height(item: MobilePageItem, width: Float): Float = when (item) {
        is MobilePageItem.Block -> blockHeight(item.content, width)
        is MobilePageItem.TableRows -> {
            val heights = FloatArray(item.band.rows) { 26f }
            for (cell in item.band.cells.sortedBy { it.rowSpan }) {
                val cellWidth = (cell.column until cell.column + cell.columnSpan).sumOf {
                    (item.widths[it] / 15f).coerceIn(76f, 240f).toDouble()
                }.toFloat() - 12f
                val needed = 12f + cell.blocks.sumOf { blockHeight(it, cellWidth).toDouble() }.toFloat()
                val end = cell.row + cell.rowSpan
                val existing = (cell.row until end).sumOf { heights[it].toDouble() }.toFloat()
                if (needed > existing) heights[end - 1] += needed - existing
            }
            heights.sum()
        }
    }

    private fun blockHeight(block: RenderBlock, width: Float): Float = when (block) {
        is RenderParagraph -> paragraph(block, width, 0)
        is RenderImage -> 8f + (block.inlineExtent?.displayHeightDp(width) ?: if (block.entryName == null) 16f else width * .7f)
        is RenderHorizontalRule -> block.thicknessPoints + 8f
        is RenderTable -> block.bands().sumOf { height(MobilePageItem.TableRows(0, block.columnWidthsTwips, it), width).toDouble() }.toFloat()
    }

    private fun measure(text: AnnotatedString, style: TextStyle, width: Float): Float = measurer.measure(text, style,
        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)), density = density, skipCache = true).size.height.toFloat()
}
