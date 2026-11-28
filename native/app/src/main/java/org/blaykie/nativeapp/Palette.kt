package org.blaykie.nativeapp

/** Fixed ARGB theme values; shared with the JVM contrast test. */
internal data class Palette(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
    val background: Long, val onBackground: Long, val surface: Long, val onSurface: Long,
    val surfaceVariant: Long, val onSurfaceVariant: Long, val outline: Long, val outlineVariant: Long,
)
internal val lightPalette = Palette(
    0xFF2F5BEA, 0xFFFFFFFF, 0xFFE6ECFD, 0xFF1F3FA8,
    0xFFF4C430, 0xFF17181C, 0xFFE6ECFD, 0xFF1F3FA8,
    0xFF2F5BEA, 0xFFFFFFFF, 0xFFE6ECFD, 0xFF1F3FA8,
    0xFFB3261E, 0xFFFFFFFF, 0xFFF9DEDC, 0xFF410E0B,
    0xFFFBFAF8, 0xFF17181C, 0xFFFFFFFF, 0xFF17181C,
    0xFFEFEDE8, 0xFF5F6470, 0xFFECE9E2, 0xFFD9D6CE,
)
internal val darkPalette = Palette(
    0xFF8FA9FF, 0xFF0E1B4D, 0xFF243468, 0xFFDCE4FF,
    0xFFF4C430, 0xFF17181C, 0xFF243468, 0xFFDCE4FF,
    0xFF8FA9FF, 0xFF0E1B4D, 0xFF243468, 0xFFDCE4FF,
    0xFFF2B8B5, 0xFF601410, 0xFF8C1D18, 0xFFF9DEDC,
    0xFF121418, 0xFFECEAE4, 0xFF1A1D24, 0xFFECEAE4,
    0xFF20242C, 0xFFA7ABB6, 0xFF2B303A, 0xFF3A404C,
)

// The document renderer consumes MaterialTheme values for page ink, default text
// size and pagination. Keep its existing presentation isolated from UI tokens.
internal val documentLightPalette = Palette(
    0xFF315CEB, 0xFFFFFFFF, 0xFFE8EEFF, 0xFF193A9B,
    0xFF193A9B, 0xFFFFFFFF, 0xFFE8EEFF, 0xFF193A9B,
    0xFF193A9B, 0xFFFFFFFF, 0xFFE8EEFF, 0xFF193A9B,
    0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
    0xFFF8F9FF, 0xFF191C20, 0xFFFFFFFF, 0xFF191C20,
    0xFFE8EEFF, 0xFF3F4656, 0xFF616978, 0xFFBAC3D6,
)
internal val documentDarkPalette = Palette(
    0xFFAFC2FF, 0xFF102A78, 0xFF193A9B, 0xFFE8EEFF,
    0xFFAFC2FF, 0xFF102A78, 0xFF193A9B, 0xFFE8EEFF,
    0xFFAFC2FF, 0xFF102A78, 0xFF193A9B, 0xFFE8EEFF,
    0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
    0xFF111318, 0xFFE2E2E9, 0xFF202632, 0xFFE2E2E9,
    0xFF30394B, 0xFFD2D9EA, 0xFFA1AABD, 0xFF505A6E,
)
