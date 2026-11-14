package org.blaykie.nativeapp.citation.style

import java.io.File
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.export.optionalExportSelection
import org.blaykie.nativeapp.session.Session
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CustomStylesGateTest {
    @get:Rule val temp = TemporaryFolder()
    private data class FakeHost(val id: Int)
    private fun selected() = Session("content://source", "source.docx", "source.docx", debugCitationStyleId = "example", debugCitationLanguage = "xx-XX", debugCitationLanguageExplicit = true)

    @Test fun offDocumentAndExportNeverInvokeHostFactory() {
        val source = File(temp.root, "source.docx").apply {
            writeBytes(requireNotNull(javaClass.classLoader?.getResourceAsStream("m4c/source.docx")).use { it.readBytes() })
        }
        val document = DocxProject(source)
        assertTrue(document.blockCount > 0)
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
