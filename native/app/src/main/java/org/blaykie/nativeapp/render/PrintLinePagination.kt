package org.blaykie.nativeapp.render

import kotlin.math.floor

internal fun printLineSpacing(single: Float, spacing: Int?, rule: String?): Float {
    if (spacing == null || spacing <= 0) return single
    return when (rule) {
        "exact" -> spacing / 20f
        "atLeast" -> maxOf(single, spacing / 20f)
        else -> single * spacing / 240f
    }.coerceIn(1f, 240f)
}

/** Returns zero to start a fresh sheet. An empty sheet always makes progress, even with oversized content. */
internal fun printFittingLines(remaining: Int, available: Float, line: Float, firstExtra: Float,
                               lastExtra: Float, occupied: Boolean, keepLines: Boolean, widowControl: Boolean): Int {
    if (firstExtra + remaining * line + lastExtra <= available + .001f) return remaining
    if (occupied && keepLines) return 0
    var count = floor((available - firstExtra + .001f) / line).toInt().coerceIn(0, remaining - 1)
    if (widowControl && remaining - count == 1) count--
    if (widowControl && count < 2 && occupied) return 0
    return if (occupied) count.coerceAtLeast(0) else count.coerceIn(1, remaining)
}

internal fun movePrintKeepChain(chainHeight: Float, used: Float, capacity: Float): Boolean =
    used > 0f && chainHeight <= capacity + .001f && used + chainHeight > capacity + .001f

internal data class PrintLines(val ends: List<Int>, val height: Float, val size: Float)

/** Keep source styles and citation IDs on both sides of a page boundary. */
internal fun RenderParagraph.printLineSlice(from: Int, endLine: Int, lines: PrintLines): RenderParagraph {
    val prefix = list?.let { it.label.length + 2 } ?: 0
    val start = if (from == 0) 0 else (lines.ends[from - 1] - prefix).coerceAtLeast(0)
    var startOffset = start
    var runIndex = 0
    var runOffset = 0
    val sliced = buildList {
        for (line in from until endLine) {
            val end = (lines.ends[line] - prefix).coerceAtLeast(startOffset)
            var newline = false
            while (runIndex < runs.size && runOffset < end) {
                val run = runs[runIndex]
                val limit = runOffset + run.text.length
                val a = (startOffset - runOffset).coerceIn(0, run.text.length)
                val b = (end - runOffset).coerceIn(0, run.text.length)
                if (b > a) {
                    val text = run.text.substring(a, b)
                    newline = text.endsWith('\n')
                    val part = if (newline && line == endLine - 1) text.dropLast(1) else text
                    if (part.isNotEmpty()) add(run.copy(text = part))
                }
                if (limit > end) break
                runOffset = limit
                runIndex++
            }
            if (!newline && line < endLine - 1) add(RenderRun("\n", RunFormat()))
            startOffset = end
        }
    }
    val last = sliced.removeAt(sliced.lastIndex)
        if (last.text.length > 1) sliced += last.copy(text = last.text.dropLast(1))
    }
    val last = endLine == lines.ends.size
    return copy(runs = sliced, list = list.takeIf { from == 0 },
        leftTwips = if (from > 0 && leftTwips == 0) (list?.let { 16 + 10 * it.level } ?: 0) * 20 else leftTwips,
        hangingTwips = if (from == 0) hangingTwips else 0, firstLineTwips = if (from == 0) firstLineTwips else 0,
        beforeTwips = if (from == 0) beforeTwips else 0, afterTwips = if (last) afterTwips else 0,
        borders = borders.copy(top = borders.top.takeIf { from == 0 }, bottom = borders.bottom.takeIf { last }))
}

internal fun RenderParagraph.printBefore(previous: Int): Float =
    (beforeTwips.coerceIn(0, 4800) - previous.coerceIn(0, 4800)).coerceAtLeast(0) / 20f + borderInset(borders.top)
internal fun RenderParagraph.printAfter(): Float = afterTwips.coerceIn(0, 4800) / 20f + borderInset(borders.bottom)
