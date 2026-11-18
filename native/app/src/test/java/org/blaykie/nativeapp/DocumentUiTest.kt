package org.blaykie.nativeapp

import org.blaykie.nativeapp.session.EditOperation
import org.junit.Assert.*
import org.junit.Test

class DocumentUiTest {
    @Test fun summaryAndMarkersUseExistingOperations() {
        assertEquals("2 citations added · 3 edits", documentSummary(2, 3))
        val markers = documentMarkers(listOf(
            EditOperation(block = 2, type = "edit", text = "Changed"),
            EditOperation(block = 2, type = "citation"),
            EditOperation(block = 2, type = "citation"),
            EditOperation(block = 3, type = "edit", text = "Other"),
        ))
        assertEquals(DocumentMarker(edited = true, citations = 2), markers[2])
        assertEquals(DocumentMarker(edited = true, citations = 0), markers[3])
        assertNull(markers[4])
        assertTrue(documentMarkers(emptyList()).isEmpty())
    }
}
