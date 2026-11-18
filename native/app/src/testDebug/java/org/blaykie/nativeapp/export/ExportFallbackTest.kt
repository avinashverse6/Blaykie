package org.blaykie.nativeapp.export

import java.io.File
import kotlinx.coroutines.runBlocking
import org.blaykie.nativeapp.docx.DocxProject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportFallbackTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun notReadyRequiresExplicitChoiceBeforeProceeding() = runBlocking {
        val source = File(temp.root, "source.docx").apply {
            writeBytes(requireNotNull(this@ExportFallbackTest.javaClass.classLoader?.getResourceAsStream("m4c/source.docx")).use { it.readBytes() })
        }
        val cache = File(temp.root, "private.docx")
        val initial = ExportState(phase = ExportPhase.PREPARING, line = "double")
        val failed = try { prepareCslExport(DocxProject(source), cache, "double", false, null); fail("not-ready host was accepted"); initial }
            catch (_: IllegalArgumentException) { preparationFailed(initial) }
        assertFalse(cache.exists())
        assertEquals(ExportPhase.FALLBACK, failed.phase)
        assertTrue(failed.message.contains("No destination was written"))
        val cancel = cancelPreparation(failed)
        assertEquals(ExportPhase.IDLE, cancel.phase)
        assertFalse(cache.exists())
        val fallback = chooseBuiltIn(failed, 0)
        assertEquals(ExportPhase.CHOOSE, fallback.phase)
        assertEquals("double", fallback.line)
        assertEquals("Language: English (US)", fallback.languageSummary)
        assertFalse(cache.exists()) // selecting is not itself a write
        try { chooseBuiltIn(initial, 0); fail("fallback without failure") } catch (_: IllegalArgumentException) { }
    }
}
