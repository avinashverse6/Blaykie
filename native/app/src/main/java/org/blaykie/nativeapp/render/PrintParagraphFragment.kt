package org.blaykie.nativeapp.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

internal data class PrintInk(val paper: DarkPaper?, val background: Color, val foreground: Color,
                             val citationBackground: Color, val citationForeground: Color) {
    fun shade(p: RenderParagraph): Color? = paper?.let { page ->
        p.shadingRgb?.let { printColor(it, page.onSurface, page.background) }
    }

    fun span(p: RenderParagraph, run: RenderRun, font: SpanStyle): SpanStyle {
        val background = shade(p) ?: background
        val explicit = run.format.rgb?.let { Color(it or 0xff000000.toInt()) } ?: foreground
        val highlight = paper?.let { run.format.shadingRgb?.let { rgb -> printColor(rgb, foreground, background) } }
        val citation = if (paper != null) printColor(null, citationBackground, background) else citationBackground
        return font.copy(
            color = when {
                run.citationNumber != null -> if (paper != null) printColor(null, citationForeground, citation) else citationForeground
                paper != null -> printColor(run.format.rgb, foreground, highlight ?: background)
                printContrast(explicit, background) >= 3f -> explicit
                else -> foreground
            },
            background = if (run.citationNumber != null) citation else highlight ?: Color.Unspecified,
            textDecoration = if (run.format.underline == true) TextDecoration.Underline else TextDecoration.None,
        )
    }
}

@Composable
internal fun PrintParagraphFragment(p: RenderParagraph, row: PrintRow, previousAfter: Int,
                                    onParagraph: (() -> Unit)?, onCitation: ((Int) -> Unit)?) {
    val lines = checkNotNull(row.lines)
    val layout = checkNotNull(lines.layout)
    val first = row.firstLine
    val end = first + row.lineCount
    val last = end == lines.count
    val top = lines.top(first)
    val height = lines.height(first, end)
    val before = if (first == 0) p.printBefore(previousAfter) else 0f
    val after = if (last) p.printAfter() else 0f
    val density = LocalDensity.current.density
    val zoom = density / PRINT_LAYOUT_DENSITY
    val left = borderInset(p.borders.left) * density
    val source = layout.layoutInput.text
    val startOffset = layout.getLineStart(first)
    val endOffset = layout.getLineEnd(end - 1)
    val paragraphClick by rememberUpdatedState(onParagraph)
    val citationClick by rememberUpdatedState(onCitation)
    val colors = MaterialTheme.colorScheme
    val paper = LocalDarkPaper.current
    val ink = PrintInk(paper, colors.background, colors.onBackground, colors.primaryContainer, colors.onPrimaryContainer)
    val shade = ink.shade(p)
    val background = shade ?: ink.background
    // Round the cumulative positions, not each paragraph height, so fractions don't drift down the sheet.
    val base = (row.top * density).roundToInt()
    val boxHeight = ((row.top + row.paragraphHeight) * density).roundToInt() - base
    val offsetY = row.top * density - base
    val textY = offsetY + before * density
    Canvas(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val fixed = constraints.constrainHeight(boxHeight.coerceAtLeast(0))
        val child = measurable.measure(constraints.copy(minHeight = fixed, maxHeight = fixed))
        layout(child.width, child.height) { child.place(0, 0) }
    }.semantics {
        text = AnnotatedString(source.text.substring(startOffset, endOffset))
        if (onCitation != null && onParagraph != null) onClick { paragraphClick?.invoke(); true }
        if (onCitation != null) customActions = source.getStringAnnotations(PRINT_CITATION_TAG, startOffset, endOffset)
            .mapNotNull { it.item.toIntOrNull() }.distinct().map { number ->
                CustomAccessibilityAction("Citation $number") { citationClick?.invoke(number); true }
            }
    }.then(if (onParagraph == null || onCitation != null) Modifier else Modifier.clickable { paragraphClick?.invoke() })
        .then(if (onCitation == null) Modifier else Modifier.pointerInput(layout, first, end, zoom, textY, left) {
            detectTapGestures { tap ->
                val point = Offset((tap.x - left) / zoom, (tap.y - textY) / zoom + top * PRINT_LAYOUT_DENSITY)
                val line = layout.getLineForVerticalPosition(point.y)
                val offset = layout.getOffsetForPosition(point)
                val citation = if (line in first until end && tap.y >= textY && tap.y < textY + height * density) {
                    // The nearest caret can be on either side of the glyph that was tapped.
                    listOf(offset, offset - 1).firstNotNullOfOrNull { index ->
                        if (index !in layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true) ||
                            !layout.getBoundingBox(index).contains(point)) null
                        else source.getStringAnnotations(PRINT_CITATION_TAG, index, index + 1).firstOrNull()?.item?.toIntOrNull()
                    }
                } else null
                if (citation != null) citationClick?.invoke(citation) else paragraphClick?.invoke()
            }
        })) {
        val edgeTop = offsetY + (before - if (first == 0) borderInset(p.borders.top) else 0f) * density
        val edgeBottom = textY + (height + if (last) borderInset(p.borders.bottom) else 0f) * density
        if (shade != null) drawRect(shade, Offset(0f, edgeTop), Size(size.width, edgeBottom - edgeTop))
        fun edge(border: RenderBorder?, horizontal: Boolean, far: Boolean) {
            if (border == null) return
            val stroke = (border.widthEighthPoints / 8f * density).coerceAtLeast(1f)
            val cross = if (horizontal) {
                if (far) edgeBottom - stroke / 2 else edgeTop + stroke / 2
            } else if (far) size.width - stroke / 2 else stroke / 2
            val color = if (paper != null) printColor(border.rgb, ink.foreground, background)
                else border.rgb?.let { Color(it or 0xff000000.toInt()) } ?: ink.foreground
            drawLine(color, if (horizontal) Offset(0f, cross) else Offset(cross, edgeTop),
                if (horizontal) Offset(size.width, cross) else Offset(cross, edgeBottom), stroke)
        }
        if (first == 0) edge(p.borders.top, horizontal = true, far = false)
        if (last) edge(p.borders.bottom, horizontal = true, far = true)
        edge(p.borders.left, horizontal = false, far = false)
        edge(p.borders.right, horizontal = false, far = true)
        // Mask only the other pages' lines. The sheet owns the horizontal clipping boundary.
        val paint: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
            translate(left, textY) {
                scale(zoom, zoom, pivot = Offset.Zero) {
                    drawText(layout, topLeft = Offset(0f, -top * PRINT_LAYOUT_DENSITY))
                }
            }
        }
        if (first == 0 && last) paint()
        else clipRect(left = -size.width, top = textY, right = size.width * 2, bottom = textY + height * density, block = paint)
    }
}
