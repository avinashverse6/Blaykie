package org.blaykie.nativeapp.render

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Present only for theme-following dark paper, never for Mobile or white paper. */
internal val LocalDarkPaper = staticCompositionLocalOf<DarkPaper?> { null }
internal data class DarkPaper(val background: Color, val onSurface: Color)

internal fun printContrast(foreground: Color, background: Color): Float {
    val a = foreground.luminance()
    val b = background.luminance()
    return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
}

internal fun printColor(rgb: Int?, automatic: Color, background: Color): Color {
    val color = rgb?.let { Color(it or 0xff000000.toInt()) } ?: automatic
    if (printContrast(color, background) >= 3f) return color
    val endpoint = if (printContrast(Color.White, background) >= printContrast(Color.Black, background)) 1f else 0f
    fun mix(amount: Float) = Color(color.red + (endpoint - color.red) * amount,
        color.green + (endpoint - color.green) * amount, color.blue + (endpoint - color.blue) * amount)
    var low = 0f
    var high = 1f
    repeat(12) {
        val mid = (low + high) / 2
        if (printContrast(mix(mid), background) >= 3f) high = mid else low = mid
    }
    return mix(high)
}
