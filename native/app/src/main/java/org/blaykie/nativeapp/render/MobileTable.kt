package org.blaykie.nativeapp.render

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import java.io.File

internal sealed interface MobilePageItem {
    val blockIndex: Int
    data class Block(override val blockIndex: Int, val content: RenderBlock, val editable: Boolean = false) : MobilePageItem
    data class TableRows(override val blockIndex: Int, val widths: List<Int>, val band: TableBand) : MobilePageItem
}

internal fun DocumentRenderModel.pageItems(): List<MobilePageItem> = blocks.flatMapIndexed { index, block ->
    val blockIndex = blockIndices[index]
    if (block is RenderTable) block.bands().map { MobilePageItem.TableRows(blockIndex, block.columnWidthsTwips, it) }
    else listOf(MobilePageItem.Block(blockIndex, block, block is RenderParagraph && blockIndex in editableBlocks))
}

@Composable
internal fun MobileTableBand(source: File, widths: List<Int>, band: TableBand,
                             scroll: ScrollState, fonts: MutableMap<String, FontFamily?>) {
    if (widths.isEmpty()) return
    val columns = remember(widths) { widths.map { (it / 15f).coerceIn(76f, 240f).dp } }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val paper = LocalDarkPaper.current
    Layout(
        modifier = Modifier.horizontalScroll(scroll),
        content = {
            band.cells.forEach { cell ->
                val shade = paper?.let { page -> cell.shadingRgb?.let { printColor(it, page.onSurface, page.background) } }
                CompositionLocalProvider(LocalDarkPaper provides if (shade != null) paper?.copy(background = shade) else paper) {
                    Column(Modifier.padding(6.dp)) {
                        cell.blocks.forEach { MobileCellBlock(source, it, fonts) }
                    }
                }
                Spacer(if (paper == null) Modifier.border(1.dp, outline) else Modifier.drawBehind {
                    if (shade != null) drawRect(shade)
                    val width = 1.dp.toPx().coerceAtLeast(1f)
                    val x = width / 2
                    val right = size.width - x
                    val bottom = size.height - x
                    fun edge(i: Int, from: Offset, to: Offset) = drawLine(
                        printColor(cell.borderColors.getOrNull(i), outline, shade ?: paper.background), from, to, width)
                    edge(0, Offset(0f, x), Offset(size.width, x))
                    edge(1, Offset(0f, bottom), Offset(size.width, bottom))
                    edge(2, Offset(x, 0f), Offset(x, size.height))
                    edge(3, Offset(right, 0f), Offset(right, size.height))
                })
            }
        },
    ) { children, constraints ->
        val offsets = IntArray(columns.size + 1)
        for (column in columns.indices) offsets[column + 1] = offsets[column] + columns[column].roundToPx()
        val heights = IntArray(band.rows) { 26.dp.roundToPx() }
        val content = band.cells.mapIndexed { index, cell ->
            val width = offsets[cell.column + cell.columnSpan] - offsets[cell.column]
            children[index * 2].measure(Constraints.fixedWidth(width)).also {
                if (cell.rowSpan == 1) heights[cell.row] = maxOf(heights[cell.row], it.height)
            }
        }
        for ((index, cell) in band.cells.withIndex()) if (cell.rowSpan > 1) {
            val end = cell.row + cell.rowSpan
            var available = 0
            for (row in cell.row until end) available += heights[row]
            heights[end - 1] += (content[index].height - available).coerceAtLeast(0)
        }
        val tops = IntArray(band.rows + 1)
        for (row in heights.indices) tops[row + 1] = tops[row] + heights[row]
        val borders = band.cells.mapIndexed { index, cell ->
            val width = offsets[cell.column + cell.columnSpan] - offsets[cell.column]
            val height = tops[cell.row + cell.rowSpan] - tops[cell.row]
            children[index * 2 + 1].measure(Constraints.fitPrioritizingWidth(width, width, height, height))
        }
        layout(constraints.constrainWidth(offsets.last()), constraints.constrainHeight(tops.last())) {
            for ((index, cell) in band.cells.withIndex()) {
                if (paper != null) borders[index].placeRelative(offsets[cell.column], tops[cell.row])
                content[index].placeRelative(offsets[cell.column], tops[cell.row])
                if (paper == null) borders[index].placeRelative(offsets[cell.column], tops[cell.row])
            }
        }
    }
}

@Composable
private fun MobileCellBlock(source: File, block: RenderBlock, fonts: MutableMap<String, FontFamily?>) {
    when (block) {
        is RenderParagraph -> MobileParagraph(block, fonts)
        is RenderImage -> MobileImage(source, block)
        is RenderHorizontalRule -> MobileHorizontalRule(block)
        is RenderTable -> {
            val scroll = rememberScrollState()
            val bands = remember(block) { block.bands() }
            Box {
                Column { bands.forEach { MobileTableBand(source, block.columnWidthsTwips, it, scroll, fonts) } }
            }
        }
    }
}
