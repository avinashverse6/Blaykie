package org.blaykie.nativeapp.docx

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.account.AccountSecret
import org.blaykie.nativeapp.document.DocumentRepository
import org.blaykie.nativeapp.document.OpenDocument
import org.blaykie.nativeapp.document.importDocument
import org.blaykie.nativeapp.document.openDocument
import org.blaykie.nativeapp.export.PreviewReferences
import org.blaykie.nativeapp.export.preparePreviewReferences
import org.blaykie.nativeapp.export.renderBibliographyPreview
import org.blaykie.nativeapp.session.EditOperation
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.VerifiedKey
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BibliographyMissingReferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun currentSessionCitationReopensWithItsLocalReferenceDetails() = runBlocking {
        val source = docx("<w:r><w:t>Text</w:t></w:r>")
        val repository = DocumentRepository(temp.newFolder())
        val opened = source.inputStream().use { repository.importDocument("content://test/current", "Current.docx", it) }
        val item = CitationItem("ABCD1234", "Sample title", listOf(CitationAuthor("Ada", "Lovelace")), "2020")
        opened.store.save(opened.ready.session.copy(edits = listOf(EditOperation(
            block = 0, type = "citation", position = 4, items = listOf(item),
        ))))
        val reopened = checkNotNull(repository.openDocument(opened.id))
        val ready = preparePreviewReferences(reopened.store, reopened.ready.session.sourceName, true,
            readAccount = { error("Local details should not need an account") }) as PreviewReferences.Ready
        assertFalse(ready.lookupAttempted)
        assertTrue(render(ready).end.bibliography.isNotEmpty())
    }

    @Test fun legacyBookmarkIsRecoveredOnAPrivatePreviewCopy() = runBlocking {
        val opened = legacyDocument(bookmark("ABCD1234", 1, "(Lovelace, 2020)"))
        val progress = mutableListOf<Int>()
        val ready = mockRecovery(opened, details) { progress.add(it) } as PreviewReferences.Ready
        val preview = render(ready)
        assertEquals(listOf(1), progress)
        assertTrue(ready.missing.isEmpty())
        assertTrue(preview.end.bibliography.isNotEmpty())
        assertNull(preview.missingNote)
        assertEquals(setOf("ABCD1234"), opened.ready.project.missingKeys())
        assertEquals(setOf("ABCD1234"), (opened.store.read() as org.blaykie.nativeapp.session.SessionRead.Ready).project.missingKeys())
    }

    @Test fun networkFailureAndMissingKeyAreReportedSeparatelyFromOmissions() = runBlocking {
        val opened = legacyDocument(bookmark("ABCD1234", 1, "(Lovelace, 2020)"))
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("Mock offline") }.build()
        val failed = preparePreviewReferences(opened.store, opened.ready.session.sourceName, true, ::fakeAccount,
            clientFactory = { ZoteroClient.forTesting(offline) }) as PreviewReferences.LookupFailed
        assertEquals("Could not reach Zotero", failed.message)
        assertFalse(failed.keyMissing)
        val noKey = preparePreviewReferences(opened.store, opened.ready.session.sourceName, true, { AccountRead.Missing },
            clientFactory = { error("Cannot start lookup without a key") }) as PreviewReferences.LookupFailed
        assertEquals("Could not reach Zotero", noKey.message)
        assertTrue(noKey.keyMissing)
    }

    @Test fun unreturnedItemShowsVisibleCitationAndPartialListMatchingExport() = runBlocking {
        val opened = legacyDocument(bookmark("ABCD1234", 1, "(Lovelace, 2020)") + bookmark("MISS1234", 2, "(Missing, 2021)"))
        // Personal lookup leaves one item missing, so recovery also asks for the group list (none here).
        val ready = mockRecovery(opened, details, extraResponses = listOf("[]"), expectedRequests = 2) as PreviewReferences.Ready
        val preview = render(ready)
        assertEquals(setOf("MISS1234"), ready.missing)
        assertEquals(listOf("(Missing, 2021)"), preview.missingCitations)
        assertEquals("1 citations have no reference details", preview.missingNote)
        assertTrue(preview.end.bibliography.any { p -> p.runs.any { it.text.contains("Sample title") } })
        val output = File(temp.root, "output.docx")
        ready.project.export(output, allowMissing = true)
        val exported = ZipFile(output).use { zip -> zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() } }
        assertEquals(readDocumentEnd(exported), preview.end)
    }

    private suspend fun render(ready: PreviewReferences.Ready) = renderBibliographyPreview(ready) { project, allowMissing ->
        project.buildXml(allowMissing = allowMissing)
    }

    private suspend fun mockRecovery(opened: OpenDocument, response: String,
                                     extraResponses: List<String> = emptyList(), expectedRequests: Int = 1,
                                     progress: (Int) -> Unit = {}): PreviewReferences =
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(response))
            extraResponses.forEach { server.enqueue(MockResponse().setBody(it)) }
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build())
            }.build()
            val result = preparePreviewReferences(opened.store, opened.ready.session.sourceName, true, ::fakeAccount, progress,
                clientFactory = { ZoteroClient.forTesting(transport) })
            assertEquals(expectedRequests, server.requestCount)
            result
        }

    private fun fakeAccount() = AccountRead.Present(AccountSecret("mock-only", VerifiedKey(123,
        KeyPermissions(true, false, false, false, false))))

    private val details = """[{"key":"ABCD1234","data":{"itemType":"journalArticle","title":"Sample title","date":"2020"}}]"""

    private fun bookmark(key: String, id: Int, text: String) =
        """<w:bookmarkStart w:id="$id" w:name="BLK_${key}_$id"/><w:r><w:t>$text</w:t></w:r><w:bookmarkEnd w:id="$id"/>"""

    private fun legacyDocument(paragraph: String): OpenDocument = docx(paragraph).inputStream().use {
        DocumentRepository(temp.newFolder()).importDocument("content://test/legacy", "Legacy.docx", it)
    }

    private fun docx(paragraph: String): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>$paragraph</w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
    }
}
