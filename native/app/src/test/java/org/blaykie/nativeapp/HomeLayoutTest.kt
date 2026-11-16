package org.blaykie.nativeapp

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun stepsStackForNarrowOrLargeText() {
        assertTrue(verticalHomeSteps(359f, TextSize.Normal, 1f))
        assertFalse(verticalHomeSteps(360f, TextSize.Normal, 1f))
        assertTrue(verticalHomeSteps(500f, TextSize.Large, 1f))
        assertTrue(verticalHomeSteps(500f, TextSize.Normal, 1.3f))
    }
}
