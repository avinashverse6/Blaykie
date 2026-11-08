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
        for (p in listOf(lightPalette, darkPalette)) {
            val pairs = listOf(
                "primary" to (p.onPrimary to p.primary), "primaryContainer" to (p.onPrimaryContainer to p.primaryContainer),
                "secondary" to (p.onSecondary to p.secondary), "secondaryContainer" to (p.onSecondaryContainer to p.secondaryContainer),
                "tertiary" to (p.onTertiary to p.tertiary), "tertiaryContainer" to (p.onTertiaryContainer to p.tertiaryContainer),
                "error" to (p.onError to p.error), "errorContainer" to (p.onErrorContainer to p.errorContainer),
                "surface" to (p.onSurface to p.surface), "variant" to (p.onSurfaceVariant to p.surfaceVariant),
                "section header" to (p.primary to p.surface), "table text" to (p.onSurfaceVariant to p.surfaceVariant),
                "error on surface" to (p.error to p.surface), "icon on surface" to (p.onSurfaceVariant to p.surface),
            )
            for ((name, colors) in pairs) assertTrue("$name contrast ${ratio(colors.first, colors.second)}", ratio(colors.first, colors.second) >= 4.5)
        }
    }
}
