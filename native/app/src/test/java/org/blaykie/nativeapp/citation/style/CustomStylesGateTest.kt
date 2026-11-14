package org.blaykie.nativeapp.citation.style

import org.blaykie.nativeapp.export.optionalExportSelection
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test

class CustomStylesGateTest {
    private data class FakeHost(val id: Int)
    private fun selected() = Session("content://source", "source.docx", "source.docx", debugCitationStyleId = "example", debugCitationLanguage = "xx-XX", debugCitationLanguageExplicit = true)

    @Test fun offDocumentAndExportNeverInvokeHostFactory() {
        var constructions = 0
        val gate = CustomStylesGate<FakeHost> { error("No host exists") }
        gate.setEnabled(false)
        val documentHost = gate.open { constructions++; FakeHost(constructions) }
        val previewHost = gate.open { constructions++; FakeHost(constructions) }
        val selection = optionalExportSelection(false, selected()) { constructions++; null }
        assertNull(documentHost); assertNull(previewHost); assertNull(selection)
        assertFalse(citationUsesCsl(selected(), false))
        assertEquals(0, constructions)
    }

    @Test fun disablingDestroysActiveAndPreviewHostsAndRestoresBuiltInSession() {
        var constructions = 0
        val destroyed = mutableListOf<Int>()
        val gate = CustomStylesGate<FakeHost> { destroyed.add(it.id) }
        gate.setEnabled(true)
        gate.open { FakeHost(++constructions) }
        gate.open { FakeHost(++constructions) }
        gate.setEnabled(false)
        assertEquals(setOf(1, 2), destroyed.toSet())
        val fallback = builtInSession(selected())
        assertNull(fallback.debugCitationStyleId)
        assertEquals("en-US", fallback.debugCitationLanguage)
        assertFalse(fallback.debugCitationLanguageExplicit)
        assertNull(gate.open { FakeHost(++constructions) })
        assertEquals(2, constructions)
        gate.setEnabled(false)
        assertEquals(2, destroyed.size)
    }
}
