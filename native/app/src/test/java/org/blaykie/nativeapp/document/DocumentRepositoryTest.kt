package org.blaykie.nativeapp.document

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.blaykie.nativeapp.docx.BibliographyOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun pickedDocx(): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>""".toByteArray())
            zip.closeEntry()
        }
    }

    private fun stateFile(document: StoredDocument) = File(document.workingDocx.parentFile, "state.json")

    @Test fun printChoiceRoundTripsPerDocumentAndOldStateDefaultsToMobile() {
        val files = temp.newFolder()
        val repository = DocumentRepository(files)
        val print = pickedDocx().inputStream().use { repository.create("Print.docx", it) }
        val mobile = pickedDocx().inputStream().use { repository.create("Old.docx", it) }
        stateFile(mobile).writeText("""{"displayName":"Old.docx","createdAtMillis":1,"lastEditedAtMillis":1}""")
        repository.saveState(print.id, print.state.copy(view = DocumentView.Print, position = DocumentPosition(3)))
        val reopened = DocumentRepository(files)
        assertEquals(DocumentView.Print, reopened.load(print.id)?.state?.view)
        assertEquals(DocumentPosition(3), reopened.load(print.id)?.state?.position)
        assertEquals(DocumentView.Mobile, reopened.load(mobile.id)?.state?.view)
    }

    @Test fun createsListsLoadsAndDeletesIndependentDocuments() {
        val filesDir = temp.newFolder()
        var clock = 10L
        val repository = DocumentRepository(filesDir) { clock }
        val picked = pickedDocx()
        val original = picked.readBytes()
        val one = picked.inputStream().use { repository.create("Same name.docx", it) }
        clock = 20L
        val two = picked.inputStream().use { repository.create("Same name.docx", it) }
        assertNotEquals(one.id, two.id)
        assertNotEquals(one.workingDocx, two.workingDocx)
        assertArrayEquals(original, one.workingDocx.readBytes())
        assertArrayEquals(original, two.workingDocx.readBytes())
        assertEquals(listOf(two, one), DocumentRepository(filesDir).list())
        assertEquals(one, DocumentRepository(filesDir).load(one.id))
        assertNull(repository.load("../${picked.name}"))
        assertFalse(repository.delete("../${picked.name}"))
        assertTrue(repository.delete(one.id))
        assertFalse(repository.delete(one.id))
        assertNull(repository.load(one.id))
        assertEquals(listOf(two), repository.list())
        assertArrayEquals(original, picked.readBytes())
    }

    @Test fun savesStateByAtomicReplacementAndPreservesMetadata() {
        val filesDir = temp.newFolder()
        var clock = 10L
        val repository = DocumentRepository(filesDir) { clock }
        val doc = pickedDocx().inputStream().use { repository.create("Draft.docx", it) }
        val stateFile = stateFile(doc)
        val oldBytes = stateFile.readBytes()
        val edit = doc.state.copy(
            displayName = "Renamed.docx", createdAtMillis = 99,
            citations = listOf(InsertedCitation(2, 13, listOf("ABC123XY", "XYZ123AB"))),
            bibliography = BibliographyOptions(false, "Sources"), citationStyle = "chicago-author-date",
            citationLanguage = "fr-FR", position = DocumentPosition(2, 88),
        )
        clock = 30L
        stateFile.inputStream().use { oldSnapshot ->
            val saved = repository.saveState(doc.id, edit)
            assertEquals(edit.copy(createdAtMillis = 10, lastEditedAtMillis = 30), saved)
            assertEquals(saved, DocumentRepository(filesDir).load(doc.id)?.state)
            // An already-open reader still sees the original inode, not an in-place rewrite.
            assertArrayEquals(oldBytes, oldSnapshot.readBytes())
        }
        assertEquals(setOf("working.docx", "state.json"), stateFile.parentFile!!.list()!!.toSet())
    }

    @Test fun failedImportDoesNotPublishPartialDocument() {
        val filesDir = temp.newFolder()
        val repository = DocumentRepository(filesDir)
        val broken = object : InputStream() {
            var read = false
            override fun read(): Int {
                if (read) throw IOException("Import interrupted")
                read = true
                return 42
            }
        }
        assertThrows(IOException::class.java) { repository.create("Broken.docx", broken) }
        assertTrue(repository.list().isEmpty())
        assertTrue(File(filesDir, "documents").list().orEmpty().isEmpty())
    }

    @Test fun missingOrCorruptStateIsSkippedAndCanBeDeleted() {
        val repository = DocumentRepository(temp.newFolder())
        val picked = pickedDocx()
        val good = picked.inputStream().use { repository.create("Good.docx", it) }
        val corrupt = picked.inputStream().use { repository.create("Corrupt.docx", it) }
        val missing = picked.inputStream().use { repository.create("Missing.docx", it) }
        stateFile(corrupt).writeText("{ broken")
        assertTrue(stateFile(missing).delete())
        assertNull(repository.load(corrupt.id))
        assertNull(repository.load(missing.id))
        assertNull(repository.saveState(missing.id, missing.state))
        assertEquals(listOf(good), repository.list())
        assertTrue(repository.delete(corrupt.id))
        assertTrue(repository.delete(missing.id))
    }

    @Test fun stateContainsOnlyDocumentMetadataAndRejectsKeyLikeCitationData() {
        val repository = DocumentRepository(temp.newFolder())
        val doc = pickedDocx().inputStream().use { repository.create("Draft.docx", it) }
        val secret = "1234567890ABCDEF1234567890ABCDEF"
        val stateFile = stateFile(doc)
        val item = InsertedCitation(0, 0, listOf("ABC123XY"))
        repository.saveState(doc.id, doc.state.copy(citations = listOf(item)))
        val before = stateFile.readText()
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveState(doc.id, doc.state.copy(citations = listOf(item.copy(zoteroItemIds = listOf(secret)))))
        }
        assertEquals(before, stateFile.readText())
        assertFalse(before.contains(secret))
        assertEquals(setOf("displayName", "createdAtMillis", "lastEditedAtMillis", "citations", "bibliography",
            "citationStyle", "citationLanguage", "position", "schemaVersion", "citationLanguageExplicit"), Json.parseToJsonElement(before).jsonObject.keys)
    }
}
