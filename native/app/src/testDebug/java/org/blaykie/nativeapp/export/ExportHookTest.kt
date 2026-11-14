package org.blaykie.nativeapp.export

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.blaykie.nativeapp.citation.processor.BuiltInApaProcessor
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.docx.DocxProject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportHookTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun selectedProcessorPreparesAndReportsDetailsWithoutChangingSource() = runBlocking {
        val source = File(temp.root, "source.docx")
        source.writeBytes(requireNotNull(javaClass.classLoader?.getResourceAsStream("m4c/source.docx")).use { it.readBytes() })
        val before = source.readBytes()
        val project = DocxProject(source)
        val position = project.blockText(1).length
        project.insertCitation(1, position, position, listOf(CitationItem("test", "Title", listOf(CitationAuthor("Alex", "Example")), "2020")))
        val output = File(temp.root, "output.docx")
        val details = PlatformExportHook.prepare(project, output, "match", false, ProcessorSelection(BuiltInApaProcessor, "Test style", "xx-XX"))
        assertEquals("Test style", details.title)
        assertEquals("Language: xx-XX", details.languageSummary)
        assertEquals(0, details.legacyCount)
        assertEquals(" Style: Test style. 0 earlier citation(s) left unchanged.", PlatformExportHook.completionSuffix(details))
        DocxProject(output)
        assertArrayEquals(before, source.readBytes())
        ZipFile(source).use { from -> ZipFile(output).use { to ->
            val names = from.entries().asSequence().map { it.name }.toSet()
            assertEquals(names, to.entries().asSequence().map { it.name }.toSet())
            names.filter { it != "word/document.xml" }.forEach { name ->
                assertArrayEquals(name, from.getInputStream(from.getEntry(name)).use { it.readBytes() }, to.getInputStream(to.getEntry(name)).use { it.readBytes() })
            }
        } }
    }
    @Test fun unavailableSelectionFailsWithoutAFile() = runBlocking {
        val source = File(temp.root, "source.docx")
        source.writeBytes(requireNotNull(javaClass.classLoader?.getResourceAsStream("m4c/source.docx")).use { it.readBytes() })
        val output = File(temp.root, "output.docx")
        try { PlatformExportHook.prepare(DocxProject(source), output, "match", false, ProcessorSelection(null)); fail("unavailable processor accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
        val failed = PlatformExportHook.preparationFailed(ExportState(phase = ExportPhase.PREPARING))
        assertEquals(ExportPhase.FALLBACK, failed.phase)
        assertEquals(ExportPhase.CHOOSE, PlatformExportHook.chooseBuiltIn(failed, 0).phase)
        assertEquals(ExportPhase.IDLE, PlatformExportHook.cancelPreparation(failed).phase)
    }
}
