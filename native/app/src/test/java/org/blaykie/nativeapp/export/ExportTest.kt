package org.blaykie.nativeapp.export

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.SocketPolicy
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("m4c/$name")!!.use { it.readBytes() }
    private fun file(name: String): File = File(temp.root, name).apply { writeBytes(resource(name)) }
    private fun expect(): JsonObject = Json.parseToJsonElement(resource("expect.json").toString(Charsets.UTF_8)) as JsonObject
    private fun item(): ZoteroItem {
        val obj = expect()["item"] as JsonObject
        return ZoteroItem((obj["key"] as JsonPrimitive).content, obj["data"] as JsonObject)
    }
    private fun contents(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } }
    }
    private fun assertZipEqual(golden: ByteArray, output: File) {
        val expected = File(temp.root, "expected.docx").apply { writeBytes(golden) }
        val a = contents(expected); val b = contents(output)
        assertEquals(a.keys.toList(), b.keys.toList())
        for (name in a.keys) assertArrayEquals(name, a.getValue(name), b.getValue(name))
    }
    @Test fun pythonGoldenAllThreeSpacingOptionsWithUnicodeAndCitation() {
        val source = file("source.docx"); val before = source.readBytes(); val project = DocxProject(source)
        val pos = project.blockText(1).length; project.insertCitation(1, pos, pos, listOf(item().citationItem()))
        for ((line, golden) in listOf("match" to "golden-match.docx", "1.5" to "golden-1_5.docx", "double" to "golden-double.docx")) {
            val out = File(temp.root, "output-$line.docx"); project.export(out, line)
            DocxProject(out); assertZipEqual(resource(golden), out); assertArrayEquals(before, source.readBytes())
        }
        try { project.export(source); fail("Source overwrite must be rejected") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun pythonGoldenWarnings() {
        val project = DocxProject(file("warnings.docx"))
        val expected = expect()["warnings"] as JsonObject
        assertEquals(expected["reference_headings"].toString(), project.existingReferenceHeadings().toString())
        assertEquals((expected["has_blaykie_bibliography"] as JsonPrimitive).content.toBoolean(), project.hasBlaykieBibliography())
        assertEquals((expected["tracked_changes"] as JsonPrimitive).content.toBoolean(), project.hasTrackedChanges())
        assertEquals((expected["zotero_fields"] as JsonPrimitive).content.toBoolean(), project.hasZoteroFields())
    }
    @Test fun pythonGoldenMissingReferenceRecoveryUsingMockWebServer() {
        val source = file("golden-match.docx"); val before = source.readBytes(); val project = DocxProject(source)
        assertEquals(setOf("ONE1"), project.missingKeys())
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("[${expect()["item"]}]").addHeader("Content-Type", "application/json"))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build())
            }.build()
            val result = recoverReferences(project, ZoteroClient.forTesting(transport), 123, "private-key")
            assertTrue(result is ZoteroResult.Success); assertEquals(0, (result as ZoteroResult.Success).value)
            assertTrue(project.missingKeys().isEmpty())
            val out = File(temp.root, "recovered.docx"); project.export(out)
            assertZipEqual(resource("golden-recovered.docx"), out)
            assertArrayEquals(before, source.readBytes())
        } finally { server.shutdown() }
    }
    @Test fun missingReferencesMayBeOmittedAndCounted() {
        val project = DocxProject(file("golden-match.docx")); val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("[]"))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val r = chain.request(); chain.proceed(r.newBuilder().url(server.url(r.url.encodedPath + "?" + r.url.encodedQuery)).build())
            }.build()
            val result = recoverReferences(project, ZoteroClient.forTesting(transport), 123, "private-key") as ZoteroResult.Success
            assertEquals(1, result.value)
            val out = File(temp.root, "partial.docx"); project.export(out, allowMissing = true); DocxProject(out)
            assertZipEqual(resource("golden-omitted.docx"), out)
        } finally { server.shutdown() }
    }
    @Test fun emptyDocumentExportsWithoutCitationsOrEdits() {
        val source = file("source.docx"); val project = DocxProject(source); val before = source.readBytes()
        val out = File(temp.root, "no-edits.docx"); project.export(out); assertZipEqual(source.readBytes(), out)
        assertArrayEquals(before, source.readBytes()); assertTrue(project.citations().isEmpty())
    }
    @Test fun filenameMatchesPythonRulesAndCollisionIdentity() {
        assertEquals("My_paper_Blaykie.docx", exportName("My paper.docx"))
        assertEquals("Document_Blaykie.docx", exportName("नमस्ते.docx"))
        assertEquals("Report_Blaykie.docx", exportName("Report!.docx"))
        assertTrue(sameDocument("content://one", "content://one", null, null))
        assertTrue(sameDocument("a", "b", "provider" to "123", "provider" to "123"))
        assertFalse(sameDocument("a", "b", "provider" to "123", "provider" to "456"))
        // A same-named picker result with a different identity is not the source.
        assertFalse(sameDocument("content://provider/document/source", "content://provider/document/collision", "provider" to "source", "provider" to "collision"))
    }
    @Test fun exportSingleFlightSurvivesEveryBusyPhase() {
        assertTrue(mayStartExport(ExportPhase.IDLE)); assertTrue(mayStartExport(ExportPhase.DONE))
        for (phase in listOf(ExportPhase.PREPARING, ExportPhase.CHOOSE, ExportPhase.WAITING, ExportPhase.WRITING)) assertFalse(mayStartExport(phase))
    }
    @Test fun cleanupConfinedToShareFolder() {
        val other = File(temp.root, "keep.txt").apply { writeText("keep") }
        cleanExportCache(temp.root); val folder = File(temp.root, CACHE_FOLDER)
        File(folder, "old.docx").writeText("old"); cleanExportCache(temp.root)
        assertTrue(folder.listFiles()!!.isEmpty()); assertEquals("keep", other.readText())
    }
    @Test fun verificationAndSourceConflictHappenBeforeDestinationIsOpened() {
        val source = file("source.docx"); val before = source.readBytes(); val project = DocxProject(source)
        val privateFile = File(temp.root, "private.docx"); var opened = false
        try {
            writeVerifiedExport(project, privateFile, "match", false, true, {}, { opened = true; ByteArrayOutputStream() })
            fail("Source destination must be refused")
        } catch (_: SourceDestinationConflict) { }
        assertFalse(opened); assertFalse(privateFile.exists()); assertArrayEquals(before, source.readBytes())
        val destination = ByteArrayOutputStream()
        writeVerifiedExport(project, privateFile, "match", false, false, {}, {
            assertTrue(privateFile.exists()); DocxProject(privateFile); opened = true; destination
        })
        assertTrue(opened); assertArrayEquals(privateFile.readBytes(), destination.toByteArray()); assertArrayEquals(before, source.readBytes())
    }
    @Test fun failedDestinationCopyThrowsAndNeverChangesSource() {
        val source = file("source.docx"); val before = source.readBytes(); val project = DocxProject(source)
        var copyStarted = false
        try {
            writeVerifiedExport(project, File(temp.root, "private.docx"), "match", false, false, { copyStarted = true }, {
                object : OutputStream() { override fun write(b: Int) { throw IOException("ENOSPC") } }
            }); fail("Copy failure must not be reported as success")
        } catch (e: IOException) { assertTrue(copyStarted); assertEquals("Not enough space to export the document.", exportError(e, FailureStage.DESTINATION)) }
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun missingReferenceLookupCanBeCancelled() {
        val project = DocxProject(file("golden-match.docx")); val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val r = chain.request(); chain.proceed(r.newBuilder().url(server.url(r.url.encodedPath + "?" + r.url.encodedQuery)).build())
            }.build()
            val client = ZoteroClient.forTesting(transport); val result = AtomicReference<ZoteroResult<Int>>()
            val worker = Thread { result.set(recoverReferences(project, client, 123, "private-key")) }
            worker.start(); assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); client.cancel(); worker.join(5000)
            assertFalse(worker.isAlive); assertSame(ZoteroResult.Cancelled, result.get()); assertEquals(setOf("ONE1"), project.missingKeys())
        } finally { server.shutdown() }
    }
    @Test fun errorMessagesNeverRevealExceptionText() {
        assertEquals("Not enough space to export the document.", exportError(IOException("ENOSPC SECRET"), FailureStage.DESTINATION))
        assertEquals("The source file is missing or access was revoked.", exportError(FileNotFoundException("SECRET"), FailureStage.SOURCE))
        assertEquals("The destination is not writable. The file may be incomplete.", exportError(SecurityException("SECRET"), FailureStage.DESTINATION))
        assertFalse(exportError(IOException("SECRET"), FailureStage.PRIVATE).contains("SECRET"))
    }
}
