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
        background = Color(p.surface), onBackground = Color(p.onSurface), surface = Color(p.surface), onSurface = Color(p.onSurface),
        surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant), outline = Color(p.outline), outlineVariant = Color(p.outlineVariant),
        surfaceTint = Color(p.primary), inversePrimary = Color(if (dark) lightPalette.primary else darkPalette.primary),
        inverseSurface = Color(if (dark) lightPalette.onSurface else darkPalette.onSurface), inverseOnSurface = Color(if (dark) lightPalette.surface else darkPalette.surface),
        surfaceBright = Color(p.surface), surfaceDim = Color(p.surface), surfaceContainerLowest = Color(p.surface), surfaceContainerLow = Color(p.surface),
        surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surfaceVariant), surfaceContainerHighest = Color(p.surfaceVariant),
        scrim = Color.Black,
    )
}

// Temporary system serif until the official font archive is provided.
private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
internal val visualTypography = Typography(
    displayLarge = text(57,64), displayMedium = text(45,52), displaySmall = text(36,44),
    headlineLarge = text(32,40), headlineMedium = text(28,36), headlineSmall = text(24,32),
    titleLarge = text(22,28), titleMedium = text(18,24,FontWeight.Medium), titleSmall = text(16,22,FontWeight.Medium),
    bodyLarge = text(18,26), bodyMedium = text(16,24), bodySmall = text(14,20),
    labelLarge = text(16,22,FontWeight.Medium), labelMedium = text(14,20,FontWeight.Medium), labelSmall = text(12,18,FontWeight.Medium),
)
