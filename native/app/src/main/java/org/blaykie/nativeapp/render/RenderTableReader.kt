package org.blaykie.nativeapp.render

import org.w3c.dom.Element

private const val MAX_COLUMNS = 128
private const val DEFAULT_WIDTH_TWIPS = 1800

internal fun table(table: Element, readBlocks: (Element) -> List<RenderBlock>): RenderTable {
    val widths = table.get("tblGrid")?.children()?.filter { it.word("gridCol") }?.take(MAX_COLUMNS)
        ?.map { it.attr("w")?.toIntOrNull()?.takeIf { width -> width > 0 } ?: DEFAULT_WIDTH_TWIPS }
        ?.toMutableList() ?: mutableListOf()
    data class Cell(val row: Int, val column: Int, val span: Int, val blocks: List<RenderBlock>, var rows: Int = 1)
    val allCells = mutableListOf<Cell>()
    var previous = emptyMap<Int, Cell>()
    var row = 0
    for (tr in table.children().filter { it.word("tr") }) {
        var column = (tr.get("trPr").get("gridBefore").attr("val")?.toIntOrNull() ?: 0).coerceIn(0, MAX_COLUMNS)
        val merges = mutableMapOf<Int, Cell>()
        for (tc in tr.children().filter { it.word("tc") }) {
            if (column >= MAX_COLUMNS) break
            val properties = tc.get("tcPr")
            val span = (properties.get("gridSpan").attr("val")?.toIntOrNull() ?: 1).coerceIn(1, MAX_COLUMNS - column)
            val vertical = properties.get("vMerge")
            val restart = vertical.attr("val") == "restart"
            val continuation = vertical != null && !restart
            val joined = previous[column]?.takeIf { continuation && it.span == span }
            if (joined != null) {
                joined.rows++
                merges[column] = joined
            } else {
                val cell = Cell(row, column, span, readBlocks(tc))
                allCells.add(cell)
                if (restart) merges[column] = cell
            }
            val cellWidth = properties.get("tcW")?.takeIf { it.attr("type") == "dxa" }
                .attr("w")?.toIntOrNull()?.takeIf { it > 0 }?.div(span)
            repeat((column + span - widths.size).coerceAtLeast(0)) { widths.add(cellWidth ?: DEFAULT_WIDTH_TWIPS) }
            column += span
        }
        previous = merges
        row++
    }
    return RenderTable(widths, row, allCells.map { RenderCell(it.row, it.column, it.rows, it.span, it.blocks) })
}

internal data class TableBand(val rows: Int, val cells: List<RenderCell>)

/** Keep merged rows together when possible, but never compose an unbounded vertical merge in one item. */
internal fun RenderTable.bands(): List<TableBand> {
    if (rowCount == 0) return emptyList()
    val mergeEnd = IntArray(rowCount)
    for (cell in cells) mergeEnd[cell.row] = maxOf(mergeEnd[cell.row], cell.row + cell.rowSpan)
    val bandStarts = mutableListOf<Int>()
    val bandAtRow = IntArray(rowCount)
    var start = 0
    var carry = 0
    while (start < rowCount) {
        val limit = minOf(start + 24, rowCount)
        val index = bandStarts.size
        bandStarts.add(start)
        var end = minOf(maxOf(start + 1, carry), limit)
        var row = start
        while (row < end) {
            bandAtRow[row] = index
            carry = maxOf(carry, mergeEnd[row])
            end = minOf(maxOf(end, carry), limit)
            row++
        }
        start = end
    }
    val buckets = List(bandStarts.size) { mutableListOf<RenderCell>() }
    for (cell in cells) {
        var row = cell.row
        val end = row + cell.rowSpan
        while (row < end) {
            val band = bandAtRow[row]
            val stop = minOf(end, bandStarts.getOrElse(band + 1) { rowCount })
            buckets[band].add(cell.copy(row = row - bandStarts[band], rowSpan = stop - row,
                blocks = if (row == cell.row) cell.blocks else emptyList()))
            row = stop
        }
    }
    return bandStarts.mapIndexed { index, first -> TableBand(bandStarts.getOrElse(index + 1) { rowCount } - first, buckets[index]) }
}
