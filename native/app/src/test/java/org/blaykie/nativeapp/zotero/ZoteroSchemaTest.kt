package org.blaykie.nativeapp.zotero

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.library.groupedTypes
import org.blaykie.nativeapp.library.titleFieldOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ZoteroSchemaTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroSchemaClient

    @Before fun setup() {
        server = MockWebServer(); server.start()
        client = ZoteroSchemaClient.forTesting(OkHttpClient(), server.url("/").toString())
    }
    @After fun teardown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test fun itemTypesExcludeAttachmentNoteAnnotation() {
        server.enqueue(ok("""[
            {"itemType":"journalArticle","localized":"Journal Article"},
            {"itemType":"attachment","localized":"Attachment"},
            {"itemType":"note","localized":"Note"},
            {"itemType":"annotation","localized":"Annotation"},
            {"itemType":"artwork","localized":"Artwork"},
            {"itemType":"book","localized":"Book"}]"""))
        val result = client.itemTypes() as SchemaResult.Success<*>
        assertFalse(result.basic)
        assertEquals(listOf("journalArticle", "artwork", "book"), result.value.map { it.type })
        // No API key or credentials are sent to the public schema endpoints.
        val request = server.takeRequest()
        assertNull(request.getHeader("Zotero-API-Key"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Blaykie/"))
        assertEquals("en-US", request.requestUrl!!.queryParameter("locale"))
    }

    @Test fun commonGroupComesFirstThenMoreAlphabetical() {
        val types = listOf(
            ItemTypeInfo("artwork", "Artwork"), ItemTypeInfo("book", "Book"),
            ItemTypeInfo("case", "Case"), ItemTypeInfo("journalArticle", "Journal Article"),
            ItemTypeInfo("dataset", "Dataset"), ItemTypeInfo("thesis", "Thesis"),
            ItemTypeInfo("bill", "Bill"))
        val (common, more) = groupedTypes(types)
        assertEquals(listOf("journalArticle", "book", "thesis", "dataset"), common.map { it.type })
        assertEquals(listOf("artwork", "bill", "case"), more.map { it.type })
    }

    @Test fun schemaUsesTemplateFieldsLabelsAndCreatorTypes() {
        server.enqueue(ok("""[
            {"field":"title","localized":"Title"},
            {"field":"bookTitle","localized":"Book Title","baseField":"publicationTitle"},
            {"field":"publisher","localized":"Publisher"}]"""))
        server.enqueue(ok("""[
            {"creatorType":"author","localized":"Author","primary":true},
            {"creatorType":"bookAuthor","localized":"Book Author"},
            {"creatorType":"editor","localized":"Editor"}]"""))
        server.enqueue(ok("""[{"key":"","version":0,"itemType":"bookSection","title":"",
            "abstractNote":"","bookTitle":"","publisher":"","dateAdded":"","dateModified":"",
            "creators":[],"tags":[],"collections":[],"relations":{}}]"""))
        val schema = (client.typeSchema("bookSection") as SchemaResult.Success<*>).value
        // Only template fields are offered (dateAdded/dateModified/creators etc. hidden).
        assertEquals(listOf("title", "abstractNote", "bookTitle", "publisher"), schema.fields.map { it.name })
        assertEquals("publicationTitle", schema.fields.first { it.name == "bookTitle" }.baseField)
        assertEquals("Book Title", schema.fields.first { it.name == "bookTitle" }.label)
        assertEquals("author", schema.primaryCreatorType())
        assertEquals(listOf("author", "bookAuthor", "editor"), schema.creatorTypes.map { it.type })
        assertEquals("title", titleFieldOf(schema)?.name)
    }

    @Test fun caseTitleComesFromBaseMappedField() {
        server.enqueue(ok("""[
            {"field":"caseName","localized":"Case Name","baseField":"title"},
            {"field":"court","localized":"Court"}]"""))
        server.enqueue(ok("""[{"creatorType":"author","localized":"Author"},{"creatorType":"counsel","localized":"Counsel"}]"""))
        server.enqueue(ok("""[{"itemType":"case","caseName":"","court":"","creators":[]}]"""))
        server.enqueue(ok("""[{"itemType":"case","localized":"Case"}]"""))
        val schema = (client.typeSchema("case") as SchemaResult.Success<*>).value
        assertEquals("caseName", titleFieldOf(schema)?.name)
        assertEquals("author", schema.primaryCreatorType())
    }

    @Test fun schemaFailureFallsBackToBasicTypes() {
        repeat(9) { server.enqueue(MockResponse().setResponseCode(500)) }
        val types = client.itemTypes() as SchemaResult.Success<*>
        assertTrue(types.basic)
        assertEquals(11, types.value.size)
        assertTrue(types.value.any { it.type == "journalArticle" })
        val thesis = (client.typeSchema("thesis") as SchemaResult.Success<*>).value
        assertTrue(thesis.basic)
        assertTrue(thesis.fields.any { it.name == "university" })
        assertTrue(thesis.fields.any { it.name == "thesisType" })
    }

    @Test fun schemaFailureForUnknownTypeReportsHttpStatus() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val result = client.typeSchema("artwork")
        assertTrue(result is SchemaResult.HttpError)
        assertEquals(503, (result as SchemaResult.HttpError).status)
    }

    @Test fun templateIsAuthoritativeOverFieldsList() {
        // The fields endpoint mentions DOI, but the presentation template does not have it.
        server.enqueue(ok("""[
            {"field":"title","localized":"Title"},
            {"field":"meetingName","localized":"Meeting Name"},
            {"field":"DOI","localized":"DOI"}]"""))
        server.enqueue(ok("""[{"creatorType":"presenter","localized":"Presenter","primary":true},
            {"creatorType":"contributor","localized":"Contributor"}]"""))
        server.enqueue(ok("""[{"itemType":"presentation","title":"","presentationType":"","meetingName":"",
            "place":"","date":"","creators":[]}]"""))
        val schema = (client.typeSchema("presentation") as SchemaResult.Success<*>).value
        assertFalse(schema.fields.any { it.name == "DOI" })
        assertTrue(schema.fields.any { it.name == "meetingName" })
        assertEquals("presenter", schema.primaryCreatorType())
    }
}
