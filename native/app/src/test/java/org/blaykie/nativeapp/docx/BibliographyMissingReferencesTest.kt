package org.blaykie.nativeapp.docx

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.document.DocumentRepository
import org.blaykie.nativeapp.document.importDocument
import org.blaykie.nativeapp.document.openDocument
import org.blaykie.nativeapp.export.recoverReferences
import org.blaykie.nativeapp.session.EditOperation
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BibliographyMissingReferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun currentSessionCitationReopensWithItsLocalReferenceDetails() {
        val source = docx("<w:r><w:t>Text</w:t></w:r>")
        val repository = DocumentRepository(temp.newFolder())
        val opened = source.inputStream().use { repository.importDocument("content://test/current", "Current.docx", it) }
        val item = CitationItem("ABCD1234", "Sample title", listOf(CitationAuthor("Ada", "Lovelace")), "2020")
        opened.store.save(opened.ready.session.copy(edits = listOf(EditOperation(
            block = 0, type = "citation", position = 4, items = listOf(item),
        ))))
        val project = checkNotNull(repository.openDocument(opened.id)).ready.project
        assertEquals(setOf("ABCD1234"), project.presentKeys())
        assertTrue(project.missingKeys().isEmpty())
        assertTrue(project.previewDocumentEnd(BibliographyOptions()).bibliography.isNotEmpty())
    }

    @Test fun legacyBookmarkBlocksPreviewEvenWhenTheReferenceIsAvailableOnline() {
        val project = legacyProject("ABCD1234")
        assertMissingPreview(project, "ABCD1234")
        assertMissingPreview(project, "ABCD1234") // Retry runs the same local preview.
        mockRecovery(project, """[{"key":"ABCD1234","data":{"itemType":"journalArticle","title":"Sample title","date":"2020"}}]""", expectedMissing = 0)
        assertTrue(project.previewDocumentEnd(BibliographyOptions()).bibliography.isNotEmpty())
    }

    @Test fun unavailableLegacyItemStillBlocksPreviewAfterASeparateLookup() {
        val project = legacyProject("MISS1234")
        assertMissingPreview(project, "MISS1234")
        mockRecovery(project, "[]", expectedMissing = 1)
        assertMissingPreview(project, "MISS1234")
    }

    private fun assertMissingPreview(project: DocxProject, key: String) {
        assertThrows(IllegalArgumentException::class.java) { project.previewDocumentEnd(BibliographyOptions()) }
        // The Compose error handler selects the specific message if this set is nonempty after failure.
        assertEquals(setOf(key), project.missingKeys())
    }

    private fun mockRecovery(project: DocxProject, response: String, expectedMissing: Int) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(response))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build())
            }.build()
            val result = recoverReferences(project, ZoteroClient.forTesting(transport), 123, "mock-only")
            assertTrue(result is ZoteroResult.Success)
            assertEquals(expectedMissing, (result as ZoteroResult.Success).value)
            assertEquals(1, server.requestCount)
        }
    }

    private fun legacyProject(key: String) = DocxProject(docx(
        """<w:bookmarkStart w:id="1" w:name="BLK_${key}_1"/><w:r><w:t>(Lovelace, 2020)</w:t></w:r><w:bookmarkEnd w:id="1"/>""",
    ))

    private fun docx(paragraph: String): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>$paragraph</w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
    }
}
