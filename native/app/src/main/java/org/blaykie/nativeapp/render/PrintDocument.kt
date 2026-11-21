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
import androidx.compose.ui.text.style.TextAlign
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
                             val reference: EndPreviewParagraph? = null, val title: Boolean = false, val label: String? = null,
                             val lines: PrintLines? = null, val lineCount: Int = 0, val input: Int = -1,
                             val firstLine: Int = 0, val paragraphHeight: Float = 0f) {
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
    private data class LayoutKey(val geometry: Key, val ink: PrintInk)
    private val heights = object : LinkedHashMap<LayoutKey, PrintLines>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, PrintLines>): Boolean = size > 4096
    }
    private val legacyHeights = object : LinkedHashMap<Key, Float>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Float>): Boolean = size > 4096
    }
    @Synchronized
    fun legacy(p: RenderParagraph, width: Float, size: Float, scale: Float, measure: () -> Float): Float =
        legacyHeights.getOrPut(Key(p, width, size, scale), measure)

    data class Snapshot(val items: List<MobilePageItem>, val setup: PageSetup, val size: Float, val scale: Float,
                        val pages: List<PrintPage>, val tailHeight: Float, val tailAfter: Int, val ink: PrintInk?)
    @Volatile var snapshot: Snapshot? = null
    @Synchronized
    fun paragraph(p: RenderParagraph, width: Float, size: Float, scale: Float, ink: PrintInk, measure: () -> PrintLines): PrintLines =
        heights.getOrPut(LayoutKey(Key(p, width, size, scale), ink), measure)
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
    val ink = PrintInk(if (darkPaper) DarkPaper(paper, paperColors.onSurface) else null,
        paperColors.background, paperColors.onBackground, paperColors.primaryContainer, paperColors.onPrimaryContainer)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val fontScale = LocalDensity.current.fontScale
    val result = footer?.content
    val renderedRevision = state.renderedRevision
    val pagination by produceState<PrintPagination?>(null, document, result, typography, measurer, fontScale, renderedRevision, ink) {
        value = withContext(Dispatchers.Default) {
            val estimate = PrintMeasure(measurer, typography.bodyLarge.fontSize.value, fontScale, state.printMeasurements, ink)
            val size = typography.bodyLarge.fontSize.value
            val body = paginatePrintBody(document.items, setup, size, fontScale, state.printMeasurements.snapshot,
                ink = ink, measureLines = { estimate.lines(it, setup.contentWidthPoints) },
                measureBlock = { estimate.height(it, setup.contentWidthPoints) }, checkActive = { ensureActive() })
            ensureActive()
            state.printMeasurements.snapshot = body
            val sheets = body.pages.dropLast(1).toMutableList()
            var rows = body.pages.lastOrNull()?.rows?.toMutableList() ?: mutableListOf()
            var cursor = body.tailHeight
            fun nextPage() {
                if (rows.isEmpty()) return
                sheets.add(PrintPage(rows))
                rows = mutableListOf()
                cursor = 0f
            }
            fun atomic(row: PrintRow, height: Float, pageBreak: Boolean = false) {
                if (pageBreak || cursor + height > setup.contentHeightPoints) nextPage()
                rows.add(row.copy(top = cursor))
                cursor += height
            }
            val end = document.items.lastOrNull()?.blockIndex ?: 0
            for (row in printBibliographyRows(end, result?.end?.bibliography.orEmpty())) {
                ensureActive()
                val height = estimate.reference(checkNotNull(row.reference), setup.contentWidthPoints)
                atomic(row, height, pageBreak = row.title)
            }
            result?.missingNote?.let { atomic(PrintRow(end, 0f, label = it), 24f) }
            result?.missingCitations?.forEach { atomic(PrintRow(end, 0f, label = it), 24f) }
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
                                                val openParagraph = target?.let { block -> { onParagraph(block) } }
                                                val openCitation = target?.let { block -> { citation: Int -> onCitation(block, citation) } }
                                                val part = item.paragraph()
                                                if (row.lines != null && part != null) {
                                                    PrintParagraphFragment(part, row, previousAfter, openParagraph, openCitation)
                                                } else DocumentBlock(document.source, item, fonts, tableScroll, previousAfter,
                                                    onParagraph = openParagraph, onCitation = openCitation)
                                                previousAfter = if (row.lines != null && row.firstLine + row.lineCount < row.lines.count) 0
                                                    else part?.afterTwips ?: 0
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

internal fun paginatePrintBody(items: List<MobilePageItem>, setup: PageSetup, size: Float, scale: Float,
                               previous: PrintMeasurements.Snapshot? = null,
                               measureLines: (RenderParagraph) -> PrintLines, measureBlock: (MobilePageItem) -> Float,
                               checkActive: () -> Unit = {}, ink: PrintInk? = null): PrintMeasurements.Snapshot {
    val capacity = setup.contentHeightPoints
    val snapshot = previous?.takeIf { it.setup == setup && it.size == size && it.scale == scale && it.ink == ink }
    val sheets = mutableListOf<PrintPage>()
    var start = 0
    var reuseTail = false
    if (snapshot != null) {
        val common = (0 until minOf(snapshot.items.size, items.size)).firstOrNull {
            snapshot.items[it] != items[it]
        } ?: minOf(snapshot.items.size, items.size)
        if (common == items.size && common == snapshot.items.size) {
            sheets.addAll(snapshot.pages.dropLast(1))
            reuseTail = snapshot.pages.isNotEmpty()
            start = common
        } else if (common > 0) {
            // A keepNext chain and the tail of a split paragraph can depend on the changed item.
            var restart = common - 1
            while (restart > 0 && items[restart - 1].paragraph()?.print?.keepNext == true) restart--
            for (sheet in snapshot.pages) {
                if (sheet.rows.last().input >= restart) break
                sheets.add(sheet)
            }
            while (sheets.isNotEmpty()) {
                val lastInput = sheets.last().rows.last().input
                val next = snapshot.pages.getOrNull(sheets.size)?.rows?.firstOrNull()?.input
                if (lastInput != next) break
                sheets.removeAt(sheets.lastIndex)
            }
            start = sheets.lastOrNull()?.rows?.lastOrNull()?.input?.plus(1) ?: 0
        }
    }
    var rows = if (reuseTail) checkNotNull(snapshot).pages.last().rows.toMutableList() else mutableListOf()
    var cursor = if (reuseTail) checkNotNull(snapshot).tailHeight else 0f
    var previousAfter = if (reuseTail) checkNotNull(snapshot).tailAfter else 0
    fun nextPage() {
        if (rows.isEmpty()) return
        sheets.add(PrintPage(rows))
        rows = mutableListOf()
        cursor = 0f
        previousAfter = 0
    }
    fun add(row: PrintRow, height: Float) {
        rows.add(row.copy(top = cursor))
        cursor += height
    }
    fun atomic(row: PrintRow, height: Float, pageBreak: Boolean = false) {
        if (pageBreak || cursor + height > capacity) nextPage()
        add(row, height)
    }
    for (input in start until items.size) {
        checkActive()
        val item = items[input]
        val paragraph = item.paragraph()
        if (paragraph == null) {
            atomic(PrintRow(item.blockIndex, 0f, item, input = input), measureBlock(item))
            previousAfter = 0
            continue
        }
        val fragments = printParagraphItems(item as MobilePageItem.Block)
        for ((index, fragment) in fragments.withIndex()) {
            val part = fragment.content as RenderParagraph
            if (index > 0 || part.pageBreakBefore) nextPage()
            val lines = measureLines(part)
            if (index == fragments.lastIndex && part.print.keepNext && rows.isNotEmpty()) {
                var chainHeight = part.printBefore(0) + lines.height(0, lines.count) + part.printAfter()
                var after = part.afterTwips
                for (successor in input + 1 until items.size) {
                    val next = items[successor].paragraph()
                    if (next?.pageBreakBefore == true || chainHeight > capacity) break
                    if (next == null) {
                        chainHeight += measureBlock(items[successor])
                        break
                    }
                    val head = next.printFragments().first()
                    val nextLines = measureLines(head)
                    val count = if (next.print.keepNext || next.print.keepLines) nextLines.count
                        else minOf(nextLines.count, if (next.print.widowControl) { if (nextLines.count == 3) 3 else 2 } else 1)
                    chainHeight += head.printBefore(after) + nextLines.height(0, count) +
                        if (count == nextLines.count) head.printAfter() else 0f
                    after = head.afterTwips
                    if (!next.print.keepNext || next.pageBreaks.isNotEmpty()) break
                }
                if (movePrintKeepChain(chainHeight, cursor, capacity)) nextPage()
            }
            var from = 0
            while (from < lines.count) {
                checkActive()
                val before = if (from == 0) part.printBefore(previousAfter) else 0f
                val count = printFittingLines(lines, from, capacity - cursor, before,
                    part.printAfter(), rows.isNotEmpty(), part.print.keepLines, part.print.widowControl)
                if (count == 0) {
                    nextPage()
                    continue
                }
                val until = from + count
                val last = until == lines.count
                val height = before + lines.height(from, until) + if (last) part.printAfter() else 0f
                add(PrintRow(item.blockIndex, 0f, fragment, lines = lines, lineCount = count,
                    input = input, firstLine = from, paragraphHeight = height), height)
                previousAfter = if (last) part.afterTwips else 0
                from = until
                if (!last) nextPage()
            }
        }
    }
    if (rows.isNotEmpty()) sheets.add(PrintPage(rows))
    return PrintMeasurements.Snapshot(items, setup, size, scale, sheets, cursor, previousAfter, ink)
}

private fun MobilePageItem.paragraph(): RenderParagraph? = (this as? MobilePageItem.Block)?.content as? RenderParagraph

private class PrintMeasure(private val measurer: TextMeasurer, private val bodySize: Float, private val fontScale: Float, private val cache: PrintMeasurements, private val ink: PrintInk) {
    // Layout in paper points independent of zoom. Text measurement uses Android shaping and font fallback.
    private val density = Density(1f, fontScale)
    private val fonts = mutableMapOf<String, FontFamily>()

    fun paragraph(p: RenderParagraph, width: Float, previousAfter: Int): Float {
        val measured = cache.legacy(p, width, bodySize, fontScale) { measureLegacyParagraph(p, width) }
        return measured + (p.beforeTwips.coerceIn(0, 4800) - previousAfter.coerceIn(0, 4800)).coerceAtLeast(0) / 20f +
            p.afterTwips.coerceIn(0, 4800) / 20f + borderInset(p.borders.top) + borderInset(p.borders.bottom)
    }

    fun lines(p: RenderParagraph, width: Float): PrintLines =
        cache.paragraph(p, width, bodySize, fontScale, ink) { measureLines(p, width) }

    private data class FontMetric(val size: Float, val family: FontFamily, val bold: Boolean, val italic: Boolean)
    private val metrics = mutableMapOf<FontMetric, Float>()
    private val paperDensity = Density(PRINT_LAYOUT_DENSITY, fontScale)

    private fun measureLines(p: RenderParagraph, width: Float): PrintLines {
        val empty = p.runs.all { it.text.isEmpty() } && p.list == null
        val mark = p.print.mark
        val size = (mark.sizePoints ?: p.defaultFontSize(bodySize)).coerceIn(6f, 100f)
        fun font(format: RunFormat) = FontMetric(format.sizePoints?.coerceIn(6f, 100f) ?: size,
            format.font?.let { fonts.getOrPut(it) { availableFont(it) } } ?: FontFamily.SansSerif,
            format.bold ?: (p.headingLevel != null), format.italic == true)
        fun FontMetric.style() = TextStyle(fontFamily = family, fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)
        fun natural(metric: FontMetric): Float = metrics.getOrPut(metric) {
            val sample = measurer.measure(AnnotatedString("Hg"), metric.style(), density = paperDensity, skipCache = true)
            (sample.getLineBottom(0) - sample.getLineTop(0)) / paperDensity.density
        }
        val markFont = font(mark)
        val single = if (empty) natural(markFont) else p.runs.maxOfOrNull { natural(font(it.format)) } ?: natural(markFont)
        val scaledLine = printLineSpacing(single, p.lineSpacing, p.lineRule)
        val line = scaledLine / fontScale
        val left = p.leftTwips.coerceIn(0, 2400) / 20f
        val hanging = p.hangingTwips.coerceIn(0, 1440) / 20f
        val indent = if (left == 0f) p.list?.let { 16f + 10 * it.level } ?: 0f else left
        val first = if (p.list != null && hanging == 0f) (indent - 16f).coerceAtLeast(0f)
            else (indent - hanging + p.firstLineTwips.coerceIn(0, 1440) / 20f).coerceAtLeast(0f)
        val text = buildAnnotatedString {
            p.list?.let { append(it.label); append("\u00a0\u00a0") }
            // Match the display's run grouping so shaping doesn't break at arbitrary XML boundaries.
            var pending: RenderRun? = null
            fun flush() {
                val run = pending ?: return
                val start = length
                withStyle(ink.span(p, run, font(run.format).style().toSpanStyle())) { append(run.text) }
                run.citationNumber?.let { addStringAnnotation(PRINT_CITATION_TAG, it.toString(), start, length) }
            }
            for (run in p.runs) {
                val previous = pending
                if (previous != null && previous.format == run.format && previous.citationNumber == run.citationNumber) {
                    pending = previous.copy(text = previous.text + run.text)
                } else {
                    flush()
                    pending = run
                }
            }
            flush()
        }
        val style = (if (empty) markFont.style() else TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp)).copy(
            color = ink.foreground, lineHeight = line.sp,
            textAlign = when (p.alignment) {
                RenderAlignment.Start -> TextAlign.Start; RenderAlignment.End -> TextAlign.End
                RenderAlignment.Left -> TextAlign.Left; RenderAlignment.Right -> TextAlign.Right
                RenderAlignment.Center -> TextAlign.Center; RenderAlignment.Justify -> TextAlign.Justify
            },
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            textIndent = TextIndent(firstLine = first.sp, restLine = indent.sp))
        val widthPx = ((width - borderInset(p.borders.left) - borderInset(p.borders.right)) * paperDensity.density)
            .roundToInt().coerceIn(1, 30_000)
        val layout = measurer.measure(text, style, constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
            density = paperDensity, skipCache = true)
        return PrintLines(layout)
    }

    private fun measureLegacyParagraph(p: RenderParagraph, width: Float): Float {
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
