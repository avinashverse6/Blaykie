package org.blaykie.nativeapp.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import org.blaykie.nativeapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DocumentFontsTest {

    @Test fun timesNewRomanAliasesResolveToTinos() {
        for (name in listOf("Times New Roman", "Times", "TimesNewRoman", "TimesNewRomanPSMT",
                            "times new roman", "  Times New Roman  ")) {
            assertSame(name, documentTinos, resolveDocumentFont(name, device = null))
        }
    }

    @Test fun tinosCoversRegularBoldItalicAndBoldItalic() {
        val faces = documentTinosFonts.map { font ->
            val resource = font as ResourceFont
            Triple(resource.resId, resource.weight, resource.style)
        }
        assertEquals(listOf(
            Triple(R.font.tinos_regular, FontWeight.Normal, FontStyle.Normal),
            Triple(R.font.tinos_bold, FontWeight.Bold, FontStyle.Normal),
            Triple(R.font.tinos_italic, FontWeight.Normal, FontStyle.Italic),
            Triple(R.font.tinos_bold_italic, FontWeight.Bold, FontStyle.Italic),
        ), faces)
    }

    @Test fun otherMissingSerifFontsStillResolveToLora() {
        assertSame(documentSerif, resolveDocumentFont("Georgia", device = null))
        assertSame(documentSerif, resolveDocumentFont("Garamond", device = null))
    }

    @Test fun missingSansSerifKeepsSansFallback() {
        assertSame(FontFamily.SansSerif, resolveDocumentFont("No Such Sans Face", device = null))
    }

    @Test fun installedDeviceFontIsUsedFirst() {
        val device = FontFamily.Cursive
        assertSame(device, resolveDocumentFont("Times New Roman", device))
        assertSame(device, resolveDocumentFont("Georgia", device))
    }
}
