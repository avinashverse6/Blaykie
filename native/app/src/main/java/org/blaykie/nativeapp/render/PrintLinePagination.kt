package org.blaykie.nativeapp.render

import androidx.compose.ui.text.TextLayoutResult

internal fun printLineSpacing(single: Float, spacing: Int?, rule: String?): Float {
    if (spacing == null || spacing <= 0) return single
    return when (rule) {
        "exact" -> spacing / 20f
        "atLeast" -> maxOf(single, spacing / 20f)
        else -> single * spacing / 240f
    }.coerceIn(1f, 240f)
}

/** Returns zero to start a fresh sheet. An empty sheet always makes progress, even with oversized content. */
internal fun printFittingLines(lines: PrintLines, from: Int, available: Float, firstExtra: Float,
                               lastExtra: Float, occupied: Boolean, keepLines: Boolean, widowControl: Boolean): Int {
    val remaining = lines.count - from
    if (firstExtra + lines.height(from, lines.count) + lastExtra <= available + .001f) return remaining
    if (occupied && keepLines) return 0
    var count = 0
    while (count + 1 < remaining && firstExtra + lines.height(from, from + count + 1) <= available + .001f) count++
    if (widowControl && remaining - count == 1) count--
    if (widowControl && count < 2 && occupied) return 0
    return if (occupied) count.coerceAtLeast(0) else count.coerceIn(1, remaining)
}

internal fun movePrintKeepChain(chainHeight: Float, used: Float, capacity: Float): Boolean =
    used > 0f && chainHeight <= capacity + .001f && used + chainHeight > capacity + .001f

internal const val PRINT_LAYOUT_DENSITY = 4f
internal const val PRINT_CITATION_TAG = "print-citation"

internal class PrintLines private constructor(private val edges: List<Float>, val layout: TextLayoutResult?) {
    constructor(layout: TextLayoutResult) : this(
        List(layout.lineCount + 1) { index ->
            (if (index == layout.lineCount) layout.getLineBottom(index - 1) else layout.getLineTop(index)) / PRINT_LAYOUT_DENSITY
        }, layout)
    // Synthetic geometry for JVM pagination tests, which do not have the Android text engine.
    constructor(edges: List<Float>) : this(edges, null)
    val count get() = edges.size - 1
    fun top(line: Int) = edges[line]
    fun height(from: Int, until: Int) = edges[until] - edges[from]
}

internal fun RenderParagraph.printBefore(previous: Int): Float =
    (beforeTwips.coerceIn(0, 4800) - previous.coerceIn(0, 4800)).coerceAtLeast(0) / 20f + borderInset(borders.top)
internal fun RenderParagraph.printAfter(): Float = afterTwips.coerceIn(0, 4800) / 20f + borderInset(borders.bottom)
