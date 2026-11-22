package org.blaykie.nativeapp.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.blaykie.nativeapp.docx.BibliographyOptions
import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.session.UndoResult
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

    @Test fun reopensBibliographyPositionCitationChoiceAndUndoHistory() {
        val filesDir = temp.newFolder()
        val repository = DocumentRepository(filesDir)
        val a = docx("First text", "Second text").use { repository.importDocument("content://docs/a", "A.docx", it) }
        val b = docx("Another text").use { repository.importDocument("content://docs/b", "B.docx", it) }
        val options = BibliographyOptions(enabled = false, heading = "Bibliography")
        val operations = listOf("ABCD1234", "EFGH5678").mapIndexed { index, key ->
            EditOperation(block = index, type = "citation", position = 0, items = listOf(CitationItem(
                key = key, title = "Sample $index", authors = listOf(CitationAuthor("Ada", "Lovelace")), date = "2020",
            )))
        }
        val edited = a.ready.session.copy(edits = operations, bibliography = options)
        a.store.save(edited)
        repository.recordSession(a.id, edited, "chicago-author-date", "fr-FR", DocumentPosition(1, 12), languageExplicit = true)

        val restarted = DocumentRepository(filesDir)
        val reopened = checkNotNull(restarted.openDocument(a.id))
        assertEquals(options, reopened.ready.session.bibliography)
        assertEquals(options, reopened.state.bibliography)
        assertEquals(DocumentPosition(1, 12), reopened.state.position)
        assertEquals("chicago-author-date", reopened.state.citationStyle)
        assertEquals("fr-FR", reopened.state.citationLanguage)
        assertTrue(reopened.state.citationLanguageExplicit)
        assertEquals(listOf(InsertedCitation(0, 0, listOf("ABCD1234")), InsertedCitation(1, 0, listOf("EFGH5678"))), reopened.state.citations)
        assertEquals(2, reopened.ready.project.citations().size)
        val undone = reopened.store.undo(reopened.ready.session) as UndoResult.Success
        restarted.recordSession(a.id, undone.session, "chicago-author-date", "fr-FR", DocumentPosition(1, 12), languageExplicit = true)
        val afterUndo = checkNotNull(restarted.openDocument(a.id))
        assertEquals(1, afterUndo.ready.project.citations().size)
        assertEquals(1, afterUndo.state.citations.size)
        val other = checkNotNull(restarted.openDocument(b.id))
        assertEquals(b.state, other.state)
        assertEquals(BibliographyOptions(), other.ready.session.bibliography)
        assertTrue(other.ready.project.citations().isEmpty())
    }

    @Test fun olderStateWithoutResumeFieldsUsesDefaults() {
        val repository = DocumentRepository(temp.newFolder())
        val document = docx("First text").use { repository.importDocument("content://docs/old", "Old.docx", it) }
        val stored = checkNotNull(repository.load(document.id))
        File(stored.workingDocx.parentFile, "state.json").writeText(
            """{"displayName":"Old.docx","createdAtMillis":1,"lastEditedAtMillis":1}""",
        )
        val restored = checkNotNull(repository.openDocument(document.id))
        assertEquals(BibliographyOptions(), restored.ready.session.bibliography)
        assertEquals(DocumentPosition(), restored.state.position)
        assertEquals("apa", restored.state.citationStyle)
        assertEquals("en-US", restored.state.citationLanguage)
        assertFalse(restored.state.citationLanguageExplicit)
        assertTrue(restored.state.citations.isEmpty())
        assertEquals(0L, restored.state.citationLibraryGroupId)
        assertEquals(1, restored.state.schemaVersion)
    }

    @Test fun deleteRemovesWorkingFolderAndResumeStateWithoutTouchingOriginal() {
        val filesDir = temp.newFolder()
        val source = temp.newFile("original.docx")
        source.writeBytes(docx("Original text").readBytes())
        val original = source.readBytes()
        val repository = DocumentRepository(filesDir)
        val document = source.inputStream().use { repository.importDocument("content://docs/original", source.name, it) }
        val stored = checkNotNull(repository.load(document.id))
        val folder = checkNotNull(stored.workingDocx.parentFile)
        assertTrue(File(folder, "state.json").isFile)
        assertTrue(File(folder, "session.json").isFile)
        assertTrue(repository.delete(document.id))
        assertFalse(folder.exists())
        val restarted = DocumentRepository(filesDir)
        assertTrue(restarted.list().isEmpty())
        assertFalse(restarted.delete(document.id))
        assertArrayEquals(original, source.readBytes())
    }

    private fun docx(vararg text: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val paragraphs = text.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs</w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
