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
        val result = client.itemTypes() as SchemaResult.Success<List<ItemTypeInfo>>
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
        val schema = (client.typeSchema("bookSection") as SchemaResult.Success<TypeSchema>).value
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
        val schema = (client.typeSchema("case") as SchemaResult.Success<TypeSchema>).value
        assertEquals("caseName", titleFieldOf(schema)?.name)
        assertEquals("author", schema.primaryCreatorType())
    }

    @Test fun schemaFailureReportsStageAndStatusInsteadOfSilentSpinner() {
        // All three schema requests fail; typeSchema must report the failing stage + HTTP status,
        // not silently hang or auto-fall-back.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val result = client.typeSchema("thesis") as SchemaResult.Failed
        assertEquals("fields", result.stage)
        assertEquals(500, result.status)
        // The built-in classic fields are still available for the "Use basic form" escape hatch.
        val basic = ZoteroSchemaFallback.schema("thesis")
        assertNotNull(basic)
        assertTrue(basic!!.fields.any { it.name == "university" })
        assertTrue(basic.fields.any { it.name == "thesisType" })
    }

    @Test fun schemaFailureForUnknownTypeReportsHttpStatus() {
        // Route the 503 deterministically to the fields request so the stage is predictable.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                when {
                    request.path.orEmpty().contains("itemTypeFields") -> MockResponse().setResponseCode(503)
                    request.path.orEmpty().contains("itemTypeCreatorTypes") -> ok("[]")
                    request.path.orEmpty().contains("items/new") -> ok("[]")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        val result = client.typeSchema("artwork") as SchemaResult.Failed
        assertEquals("fields", result.stage)
        assertEquals(503, result.status)
    }

    @Test fun labelComesFromLoadedListWithoutExtraRequest() {
        server.enqueue(ok("""[{"itemType":"journalArticle","localized":"Journal Article"}]"""))
        client.itemTypes()
        val before = server.requestCount
        server.enqueue(ok("""[{"field":"title","localized":"Title"}]"""))
        server.enqueue(ok("""[{"creatorType":"author","localized":"Author","primary":true}]"""))
        server.enqueue(ok("""[{"itemType":"journalArticle","title":"","creators":[]}]"""))
        val schema = (client.typeSchema("journalArticle", "Journal Article") as SchemaResult.Success<TypeSchema>).value
        assertEquals("Journal Article", schema.label)
        // Exactly the three schema requests were made; no fourth itemTypes request for the label.
        assertEquals(before + 3, server.requestCount)
        val paths = (before until server.requestCount).map { server.takeRequest().path.orEmpty() }
        assertTrue(paths.none { it.startsWith("/itemTypes") })
    }

    @Test fun requestNeverAnswersHitsTimeoutAndReportsStage() {
        val slow = MockResponse().setResponseCode(200).setBody("[]").setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        server.enqueue(slow); server.enqueue(slow); server.enqueue(slow)
        val client = ZoteroSchemaClient.forTesting(OkHttpClient(), server.url("/").toString(), 300, 2000)
        val result = client.typeSchema("journalArticle", "Journal Article") as SchemaResult.Failed
        assertEquals("timeout", result.exception)
        assertTrue(result.stage.isNotBlank())
    }

    @Test fun failedLoadIsNotCachedAndCanBeRetried() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok("[]")); server.enqueue(ok("[]"))
        assertTrue(client.typeSchema("thesis") is SchemaResult.Failed)
        // A later successful load must work and be returned (nothing was cached as "loading").
        server.enqueue(ok("""[{"field":"title","localized":"Title"},{"field":"university","localized":"University"}]"""))
        server.enqueue(ok("""[{"creatorType":"author","localized":"Author","primary":true}]"""))
        server.enqueue(ok("""[{"itemType":"thesis","title":"","university":"","creators":[]}]"""))
        val schema = (client.typeSchema("thesis") as SchemaResult.Success<TypeSchema>).value
        assertTrue(schema.fields.any { it.name == "university" })
    }

    @Test fun basicFormForJournalArticleHasClassicFields() {
        val schema = ZoteroSchemaFallback.schema("journalArticle")
        assertNotNull(schema)
        val names = schema!!.fields.map { it.name }
        assertTrue("title" in names)
        assertTrue("publicationTitle" in names)
        assertTrue("DOI" in names)
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
        val schema = (client.typeSchema("presentation") as SchemaResult.Success<TypeSchema>).value
        assertFalse(schema.fields.any { it.name == "DOI" })
        assertTrue(schema.fields.any { it.name == "meetingName" })
        assertEquals("presenter", schema.primaryCreatorType())
    }
}
