package org.blaykie.nativeapp.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.blaykie.nativeapp.session.EditOperation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentSessionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun separatelyImportedDocumentsResumeTheirOwnEditsAfterRename() {
        val filesDir = temp.newFolder()
        val repository = DocumentRepository(filesDir)
        val one = docx("First text").use { repository.importDocument("content://documents/first", "One.docx", it) }
        val two = docx("Second text").use { repository.importDocument("content://documents/second", "Two.docx", it) }
        one.store.save(one.ready.session.copy(edits = listOf(EditOperation(0, "First edited"))))
        two.store.save(two.ready.session.copy(edits = listOf(EditOperation(0, "Second edited"))))
        val state = checkNotNull(repository.load(one.id)).state
        repository.saveState(one.id, state.copy(displayName = "Renamed.docx"))

        val restarted = DocumentRepository(filesDir)
        assertEquals(setOf("Renamed.docx", "Two.docx"), restarted.list().map { it.state.displayName }.toSet())
        val reopenedOne = checkNotNull(restarted.openDocument(one.id)).ready
        val reopenedTwo = checkNotNull(restarted.openDocument(two.id)).ready
        assertEquals("Renamed.docx", reopenedOne.session.fileName)
        assertEquals("First edited", reopenedOne.project.blockText(0))
        assertEquals("Second edited", reopenedTwo.project.blockText(0))
        assertNotEquals(reopenedOne.session.sourceName, reopenedTwo.session.sourceName)
    }

    private fun docx(text: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
