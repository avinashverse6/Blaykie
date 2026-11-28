package org.blaykie.nativeapp.document

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DocumentsHomeTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private fun date(day: Int): Long = Calendar.getInstance(zone).apply { clear(); set(2025, 3, day, 12, 0) }.timeInMillis
    @Test fun relativeDatesAndOlder() {
        assertEquals("Edited today", editedDateLabel(date(10), date(10), zone))
        assertEquals("Edited yesterday", editedDateLabel(date(9), date(10), zone))
        assertEquals("Edited 3 days ago", editedDateLabel(date(7), date(10), zone))
        assertTrue(editedDateLabel(date(1), date(10), zone).startsWith("Edited "))
        assertFalse(editedDateLabel(date(1), date(10), zone).contains("days ago"))
    }
    @Test fun tintCycles() { assertEquals(listOf(0, 1, 2, 0, 1, 2), (0..5).map(::documentTintIndex)) }
}
