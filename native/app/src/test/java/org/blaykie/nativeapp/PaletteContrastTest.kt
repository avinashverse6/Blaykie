package org.blaykie.nativeapp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PaletteContrastTest {
    private fun luminance(color: Long): Double {
        fun component(shift: Int): Double {
            val c = ((color shr shift) and 255).toDouble() / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * component(16) + 0.7152 * component(8) + 0.0722 * component(0)
    }
    private fun ratio(a: Long, b: Long): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
    @Test fun textAndIconPairsMeetAA() {
        for ((theme, p, other) in listOf(Triple("light", lightPalette, darkPalette), Triple("dark", darkPalette, lightPalette))) {
            val textPairs = listOf(
                "primary" to (p.onPrimary to p.primary), "primaryContainer" to (p.onPrimaryContainer to p.primaryContainer),
                "secondary" to (p.onSecondary to p.secondary), "secondaryContainer" to (p.onSecondaryContainer to p.secondaryContainer),
                "tertiary" to (p.onTertiary to p.tertiary), "tertiaryContainer" to (p.onTertiaryContainer to p.tertiaryContainer),
                "error" to (p.onError to p.error), "errorContainer" to (p.onErrorContainer to p.errorContainer),
                "background" to (p.onBackground to p.background), "surface" to (p.onSurface to p.surface),
                "variant" to (p.onSurfaceVariant to p.surfaceVariant), "variant on surface" to (p.onSurfaceVariant to p.surface),
                "variant on background" to (p.onSurfaceVariant to p.background), "surface text on background" to (p.onSurface to p.background),
                "surface text on variant" to (p.onSurface to p.surfaceVariant),
                "selected text" to (p.onSurface to p.primaryContainer),
                "selected control" to (p.primary to p.primaryContainer),
                "primary on surface" to (p.primary to p.surface), "primary on background" to (p.primary to p.background),
                "error on surface" to (p.error to p.surface), "error on background" to (p.error to p.background),
                "banner error" to (p.error to p.surfaceVariant), "banner action" to (p.primary to p.surfaceVariant),
                "editor text" to (p.onSurface to p.surface), "editor tonal action" to (p.onSecondaryContainer to p.secondaryContainer),
                "editor focused outline" to (p.primary to p.surface),
                "inverse surface" to (other.surface to other.onSurface),
            )
            val iconPairs = listOf("outline on surface" to (p.outline to p.surface), "outline on background" to (p.outline to p.background), "outline on variant" to (p.outline to p.surfaceVariant))
            for ((name, colors) in textPairs) assertTrue("$theme $name contrast ${ratio(colors.first, colors.second)}", ratio(colors.first, colors.second) >= 4.5)
            for ((name, colors) in iconPairs) assertTrue("$theme $name contrast ${ratio(colors.first, colors.second)}", ratio(colors.first, colors.second) >= 3.0)
        }
    }
}
