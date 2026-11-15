package org.blaykie.nativeapp

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal fun visualColors(dark: Boolean): ColorScheme {
    val p = if (dark) darkPalette else lightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary), primaryContainer = Color(p.primaryContainer), onPrimaryContainer = Color(p.onPrimaryContainer),
        secondary = Color(p.secondary), onSecondary = Color(p.onSecondary), secondaryContainer = Color(p.secondaryContainer), onSecondaryContainer = Color(p.onSecondaryContainer),
        tertiary = Color(p.tertiary), onTertiary = Color(p.onTertiary), tertiaryContainer = Color(p.tertiaryContainer), onTertiaryContainer = Color(p.onTertiaryContainer),
        error = Color(p.error), onError = Color(p.onError), errorContainer = Color(p.errorContainer), onErrorContainer = Color(p.onErrorContainer),
        background = Color(p.background), onBackground = Color(p.onBackground), surface = Color(p.surface), onSurface = Color(p.onSurface),
        surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant), outline = Color(p.outline), outlineVariant = Color(p.outlineVariant),
        surfaceTint = Color(p.primary), inversePrimary = Color(if (dark) lightPalette.primary else darkPalette.primary),
        inverseSurface = Color(if (dark) lightPalette.onSurface else darkPalette.onSurface), inverseOnSurface = Color(if (dark) lightPalette.surface else darkPalette.surface),
        surfaceBright = Color(p.surface), surfaceDim = Color(p.surface), surfaceContainerLowest = Color(p.surface), surfaceContainerLow = Color(p.surface),
        surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surfaceVariant), surfaceContainerHighest = Color(p.surfaceVariant),
        scrim = Color.Black,
    )
}

// Keep the family centralized so a verified display font can be introduced later.
private val appFontFamily = FontFamily.SansSerif
private fun heading(size: Int, line: Int, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = appFontFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = appFontFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
internal val visualTypography = Typography(
    displayLarge = heading(57,64), displayMedium = heading(45,52), displaySmall = heading(36,44),
    headlineLarge = heading(32,40), headlineMedium = heading(28,36), headlineSmall = heading(24,32),
    titleLarge = heading(22,30), titleMedium = heading(18,26), titleSmall = heading(16,24),
    bodyLarge = body(18,26), bodyMedium = body(16,24), bodySmall = body(14,20),
    labelLarge = body(16,22,FontWeight.Medium), labelMedium = body(14,20,FontWeight.Medium), labelSmall = body(12,18,FontWeight.Medium),
)
