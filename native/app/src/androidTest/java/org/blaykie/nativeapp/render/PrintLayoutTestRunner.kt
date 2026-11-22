package org.blaykie.nativeapp.render

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** Dependency-free device check: ./gradlew :app:connectedDebugAndroidTest (Android 8+). */
class PrintLayoutTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val status = Bundle().apply {
            putString("class", javaClass.name)
            putString("test", "checkPrintLayout")
            putInt("numtests", 1)
            putInt("current", 1)
        }
        sendStatus(1, status)
        try {
            check(Build.VERSION.SDK_INT >= 26) { "Android's justification requires API 26 or later" }
            checkPrintLayout()
            result.putString("stream", "\nPrint layout geometry passed\n")
            sendStatus(0, status)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "\n${failure.stackTraceToString()}\n")
            status.putString("stack", failure.stackTraceToString())
            sendStatus(-2, status)
            finish(Activity.RESULT_OK, result)
        }
    }

    private fun checkPrintLayout() {
        val setup = PageSetup(widthTwips = 12240, heightTwips = 15840)
        val font = RunFormat(font = "Times New Roman", sizePoints = 12f)
        fun paragraph(text: String, alignment: RenderAlignment) = RenderParagraph(
            listOf(RenderRun(text, font)), alignment = alignment, lineSpacing = 360, lineRule = "auto",
            print = PrintParagraphProperties(mark = font),
        )
        val long = paragraph("The study compared a variety of methods and examples in a series of trials. ".repeat(48).trim(),
            RenderAlignment.Justify)
        val centered = paragraph("Centered heading", RenderAlignment.Center)
        val right = paragraph("Right aligned", RenderAlignment.Right)
        val cache = PrintMeasurements()
        val measure = PrintMeasure(TextMeasurer(createFontFamilyResolver(targetContext), Density(4f), LayoutDirection.Ltr),
            12f, 1f, cache, PrintInk(null, Color.White, Color.Black, Color.Yellow, Color.Black))
        val items = listOf(long, centered, right).mapIndexed { index, p -> MobilePageItem.Block(index, p) }
        val snapshot = paginatePrintBody(items, setup, 12f, 1f, measureLines = { measure.lines(it, setup.contentWidthPoints) },
            measureBlock = { error("Unexpected non-paragraph") })
        val fragments = snapshot.pages.flatMap { it.rows }.filter { it.blockIndex == 0 }
        check(fragments.size == 2) { "Expected the thesis paragraph to span two sheets: ${fragments.size}" }
        val lines = checkNotNull(fragments.first().lines)
        val layout = checkNotNull(lines.layout)
        check(fragments.all { it.lines === lines })
        val indices = fragments.flatMap { it.firstLine until it.firstLine + it.lineCount }
        check(indices == (0 until layout.lineCount).toList())
        val width = setup.contentWidthPoints * PRINT_LAYOUT_DENSITY
        for (line in indices) {
            check(layout.getLineTop(line) < layout.getLineBottom(line))
            val lastGlyph = layout.getLineEnd(line, visibleEnd = true) - 1
            val edge = layout.getBoundingBox(lastGlyph).right
            check(edge <= width + 1f) { "Line $line overflows: $edge > $width" }
            if (line != indices.last()) check(abs(width - edge) <= 2f) { "Line $line is not justified: $edge vs $width" }
        }
        for (sheet in snapshot.pages) {
            for ((a, b) in sheet.rows.zipWithNext()) check(a.top < b.top && a.top + a.paragraphHeight <= b.top + .001f)
            for (row in sheet.rows) check(row.top + row.paragraphHeight <= setup.contentHeightPoints + .001f)
        }
        for (zoom in 1..3) {
            // Draw into a small bitmap, reusing the exact production layout rather than remeasuring at zoom.
            val bitmap = ImageBitmap(32, 32)
            CanvasDrawScope().draw(Density(zoom.toFloat()), LayoutDirection.Ltr, Canvas(bitmap), androidx.compose.ui.geometry.Size(32f, 32f)) {
                scale(zoom / PRINT_LAYOUT_DENSITY, pivot = Offset.Zero) { drawText(layout, topLeft = Offset.Zero) }
            }
            check(measure.lines(long, setup.contentWidthPoints).layout === layout)
        }
        val centerLayout = checkNotNull(measure.lines(centered, setup.contentWidthPoints).layout)
        val rightLayout = checkNotNull(measure.lines(right, setup.contentWidthPoints).layout)
        check(abs(centerLayout.getLineLeft(0) + centerLayout.getLineRight(0) - width) <= 2f)
        check(abs(rightLayout.getLineRight(0) - width) <= 1f)
    }
}
