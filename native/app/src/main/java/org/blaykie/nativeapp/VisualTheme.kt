package org.blaykie.nativeapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun paletteColors(p: Palette, other: Palette, dark: Boolean, document: Boolean = false): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary), primaryContainer = Color(p.primaryContainer), onPrimaryContainer = Color(p.onPrimaryContainer),
        secondary = Color(p.secondary), onSecondary = Color(p.onSecondary), secondaryContainer = Color(p.secondaryContainer), onSecondaryContainer = Color(p.onSecondaryContainer),
        tertiary = Color(p.tertiary), onTertiary = Color(p.onTertiary), tertiaryContainer = Color(p.tertiaryContainer), onTertiaryContainer = Color(p.onTertiaryContainer),
        error = Color(p.error), onError = Color(p.onError), errorContainer = Color(p.errorContainer), onErrorContainer = Color(p.onErrorContainer),
        background = Color(p.background), onBackground = Color(p.onBackground), surface = Color(p.surface), onSurface = Color(p.onSurface),
        surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant), outline = Color(p.outline), outlineVariant = Color(p.outlineVariant),
        surfaceTint = Color(p.primary), inversePrimary = Color(other.primary),
        inverseSurface = Color(other.onSurface), inverseOnSurface = Color(if (dark && !document) p.onSurface else other.surface),
        surfaceBright = Color(p.surface), surfaceDim = Color(if (document) p.surface else p.background), surfaceContainerLowest = Color(p.surface),
        surfaceContainerLow = Color(p.surface), surfaceContainer = Color(p.surface),
        surfaceContainerHigh = Color(p.surfaceVariant), surfaceContainerHighest = Color(p.surfaceVariant),
        scrim = if (document) Color.Black else Color(0x6617181C),
    )
}

internal fun visualColors(dark: Boolean): ColorScheme = paletteColors(
    if (dark) darkPalette else lightPalette, if (dark) lightPalette else darkPalette, dark,
)

internal val BottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

internal val visualShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Android's font fallback supplies glyphs absent from Plus Jakarta Sans, including
// Devanagari and Gujarati. Do not add a fallback font that lacks those glyphs.
internal val plusJakartaFonts = listOf(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)
internal val PlusJakartaSans = FontFamily(plusJakartaFonts)
private fun uiStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = PlusJakartaSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    // TextStyle cannot distinguish scripts within mixed-language text. Zero spacing
    // preserves Indic shaping even in headings; Latin tracking can be refined per text later.
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
internal val visualTypography = Typography(
    displayLarge = uiStyle(57, 64, FontWeight.ExtraBold),
    displayMedium = uiStyle(45, 52, FontWeight.ExtraBold),
    displaySmall = uiStyle(34, 40, FontWeight.ExtraBold),
    headlineLarge = uiStyle(32, 40, FontWeight.Bold),
    headlineMedium = uiStyle(28, 34, FontWeight.ExtraBold),
    headlineSmall = uiStyle(24, 30, FontWeight.Bold),
    titleLarge = uiStyle(20, 26, FontWeight.Bold), titleMedium = uiStyle(16, 22, FontWeight.Bold),
    titleSmall = uiStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = uiStyle(16, 24), bodyMedium = uiStyle(14, 21), bodySmall = uiStyle(12, 18),
    labelLarge = uiStyle(14, 20, FontWeight.SemiBold), labelMedium = uiStyle(12, 16, FontWeight.SemiBold),
    labelSmall = uiStyle(11, 16, FontWeight.Medium),
)

/** Preserve the page renderer's existing theme-derived measurements and ink. */
@Composable
internal fun DocumentPresentationTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val p = if (dark) documentDarkPalette else documentLightPalette
    val other = if (dark) documentLightPalette else documentDarkPalette
    val documentBody = TextStyle(
        fontFamily = FontFamily(Font(R.font.lora_regular, FontWeight.Normal), Font(R.font.lora_bold, FontWeight.Bold)),
        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal,
    )
    val documentColors = paletteColors(p, other, dark, document = true)
    MaterialTheme(colorScheme = documentColors, typography = MaterialTheme.typography.copy(bodyLarge = documentBody)) {
        CompositionLocalProvider(LocalContentColor provides documentColors.onSurface, content = content)
    }
}
