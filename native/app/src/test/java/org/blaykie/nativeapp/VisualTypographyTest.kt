package org.blaykie.nativeapp

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VisualTypographyTest {
    @Test fun plusJakartaIncludesAllFiveStaticWeights() {
        val faces = plusJakartaFonts.map { font ->
            val resource = font as ResourceFont
            Triple(resource.resId, resource.weight, resource.style)
        }
        assertEquals(listOf(
            Triple(R.font.plus_jakarta_sans_regular, FontWeight.Normal, FontStyle.Normal),
            Triple(R.font.plus_jakarta_sans_medium, FontWeight.Medium, FontStyle.Normal),
            Triple(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
            Triple(R.font.plus_jakarta_sans_bold, FontWeight.Bold, FontStyle.Normal),
            Triple(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
        ), faces)
        val styles = visualTypography.let { listOf(it.displayLarge, it.displayMedium, it.displaySmall,
            it.headlineLarge, it.headlineMedium, it.headlineSmall, it.titleLarge, it.titleMedium,
            it.titleSmall, it.bodyLarge, it.bodyMedium, it.bodySmall, it.labelLarge, it.labelMedium, it.labelSmall) }
        styles.forEach { assertSame(PlusJakartaSans, it.fontFamily) }
    }

    @Test fun bundledFontLicenceMatchesTheSuppliedCopy() {
        val root = listOf(File("../.."), File("."), File("../../.."))
            .first { File(it, "native/app/build.gradle.kts").isFile }
        val original = File(root, "docs/licenses/plus_jakarta_sans_license.txt").readBytes()
        assertArrayEquals(original, File(root, "native/LICENSES/PlusJakartaSans-OFL.txt").readBytes())
        assertArrayEquals(original, File(root, "native/app/src/main/res/raw/plus_jakarta_sans_ofl.txt").readBytes())
    }
}
