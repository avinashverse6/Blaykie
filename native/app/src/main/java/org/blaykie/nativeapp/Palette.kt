package org.blaykie.nativeapp

/** Fixed ARGB theme values; shared with the JVM contrast test. */
internal data class Palette(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
    val surface: Long, val onSurface: Long, val surfaceVariant: Long, val onSurfaceVariant: Long,
    val outline: Long, val outlineVariant: Long,
)
internal val lightPalette = Palette(
    0xFF2B579A, 0xFFFFFFFF, 0xFFD8E3FF, 0xFF001B3C,
    0xFF515F79, 0xFFFFFFFF, 0xFFD8E3FF, 0xFF0D1C35,
    0xFF6A5778, 0xFFFFFFFF, 0xFFF1DAFF, 0xFF241532,
    0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
    0xFFF8F9FF, 0xFF191C20, 0xFFDFE2EB, 0xFF43474E, 0xFF73777F, 0xFFC3C6CF,
)
internal val darkPalette = Palette(
    0xFFADC6FF, 0xFF002F65, 0xFF17467F, 0xFFD8E3FF,
    0xFFB8C7E5, 0xFF233149, 0xFF394760, 0xFFD8E3FF,
    0xFFD6BDE4, 0xFF3B2949, 0xFF52405F, 0xFFF1DAFF,
    0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
    0xFF111318, 0xFFE2E2E9, 0xFF43474E, 0xFFC3C6CF, 0xFF8D9199, 0xFF43474E,
)
