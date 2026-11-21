package org.blaykie.nativeapp.render

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class PrintColorsTest {
    private val paper = Color(0xff202632)
    private val onSurface = Color(0xffe2e2e9)

    @Test fun automaticTextUsesDarkOnSurface() {
        assertEquals(onSurface, printColor(null, onSurface, paper))
    }

    @Test fun darkRedLightensJustEnoughToRead() {
        val darkRed = Color(0xff660000)
        assertTrue(printContrast(darkRed, paper) < 3f)
        val adjusted = printColor(0x660000, onSurface, paper)
        assertTrue(adjusted.red > adjusted.green)
        assertTrue(printContrast(adjusted, paper) in 3f..3.02f)
    }

    @Test fun readableMidToneIsUnchanged() {
        assertEquals(Color(0xff7a8ca5), printColor(0x7a8ca5, onSurface, paper))
    }
}
