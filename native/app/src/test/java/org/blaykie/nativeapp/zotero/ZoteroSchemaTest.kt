package org.blaykie.nativeapp.zotero

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.blaykie.nativeapp.library.groupedTypes
import org.blaykie.nativeapp.library.titleFieldOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

class ZoteroSchemaTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroSchemaClient

    @Before fun setup() {
        server = MockWebServer(); server.start()
        client = ZoteroSchemaClient.forTesting(OkHttpClient(), server.url("/").toString())
    }
    @After fun teardown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /**
     * Answers by PATH so the three parallel requests stay deterministic.
     *
     * A custom Dispatcher cannot be mixed with server.enqueue() or server.takeRequest():
     * both cast the dispatcher to QueueDispatcher and throw ClassCastException. Every
     * received request is therefore recorded in a thread-safe list inside the dispatcher.
     */
    private class Router(private val route: (String) -> MockResponse?) : Dispatcher() {
        private val received = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val requests: List<RecordedRequest> get() = synchronized(received) { received.toList() }
        val paths: List<String> get() = requests.map { it.path.orEmpty() }
        override fun dispatch(request: RecordedRequest): MockResponse {
            received.add(request)
            return route(request.path.orEmpty()) ?: MockResponse().setResponseCode(404)
        }
    }

    /** Routes each request by a substring of its path. */
    private fun route(vararg pairs: Pair<String, MockResponse>) = Router { path ->
        pairs.firstOrNull { path.contains(it.first) }?.second
    }

    /** The server keeps the connection open and never answers. */
    private fun neverAnswers() = Router { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }

    @Test fun itemTypesExcludeAttachmentNoteAnnotation() {
        val router = route("itemTypes" to ok("""[
            {"itemType":"journalArticle","localized":"Journal Article"},
            {"itemType":"attachment","localized":"Attachment"},
            {"itemType":"note","localized":"Note"},
            {"itemType":"annotation","localized":"Annotation"},
            {"itemType":"artwork","localized":"Artwork"},
            {"itemType":"book","localized":"Book"}]"""))
        server.dispatcher = router
        val result = runBlocking { client.itemTypes() } as SchemaResult.Success<List<ItemTypeInfo>>
        assertFalse(result.basic)
        assertEquals(listOf("journalArticle", "artwork", "book"), result.value.map { it.type })
        // No API key or credentials are sent to the public schema endpoints.
        assertEquals(1, router.requests.size)
        val request = router.requests[0]
        assertNull(request.getHeader("Zotero-API-Key"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Blaykie/"))
        assertEquals("en-US", request.requestUrl!!.queryParameter("locale"))
        assertEquals("/itemTypes", request.path.orEmpty().substringBefore('?'))
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
        server.dispatcher = route(
            "itemTypeFields" to ok("""[
                {"field":"title","localized":"Title"},
                {"field":"bookTitle","localized":"Book Title","baseField":"publicationTitle"},
                {"field":"publisher","localized":"Publisher"}]"""),
            "itemTypeCreatorTypes" to ok("""[
                {"creatorType":"author","localized":"Author","primary":true},
                {"creatorType":"bookAuthor","localized":"Book Author"},
                {"creatorType":"editor","localized":"Editor"}]"""),
            "items/new" to ok("""[{"key":"","version":0,"itemType":"bookSection","title":"",
                "abstractNote":"","bookTitle":"","publisher":"","dateAdded":"","dateModified":"",
                "creators":[],"tags":[],"collections":[],"relations":{}}]"""),
        )
        val schema = (runBlocking { client.typeSchema("bookSection") } as SchemaResult.Success<TypeSchema>).value
        // Only template fields are offered (dateAdded/dateModified/creators etc. hidden).
        assertEquals(listOf("title", "abstractNote", "bookTitle", "publisher"), schema.fields.map { it.name })
        assertEquals("publicationTitle", schema.fields.first { it.name == "bookTitle" }.baseField)
        assertEquals("Book Title", schema.fields.first { it.name == "bookTitle" }.label)
        assertEquals("author", schema.primaryCreatorType())
        assertEquals(listOf("author", "bookAuthor", "editor"), schema.creatorTypes.map { it.type })
        assertEquals("title", titleFieldOf(schema)?.name)
    }

    @Test fun caseTitleComesFromBaseMappedField() {
        server.dispatcher = route(
            "itemTypeFields" to ok("""[
                {"field":"caseName","localized":"Case Name","baseField":"title"},
                {"field":"court","localized":"Court"}]"""),
            "itemTypeCreatorTypes" to ok("""[{"creatorType":"author","localized":"Author"},{"creatorType":"counsel","localized":"Counsel"}]"""),
            "items/new" to ok("""[{"itemType":"case","caseName":"","court":"","creators":[]}]"""),
        )
        val schema = (runBlocking { client.typeSchema("case") } as SchemaResult.Success<TypeSchema>).value
        assertEquals("caseName", titleFieldOf(schema)?.name)
        assertEquals("author", schema.primaryCreatorType())
    }

    @Test fun schemaFailureReportsStageAndStatusInsteadOfSilentSpinner() {
        // All three schema requests fail; typeSchema must report the failing stage + HTTP status,
        // not silently hang or auto-fall-back.
        server.dispatcher = route(
            "itemTypeFields" to MockResponse().setResponseCode(500),
            "itemTypeCreatorTypes" to MockResponse().setResponseCode(500),
            "items/new" to MockResponse().setResponseCode(500),
        )
        val result = runBlocking { client.typeSchema("thesis") } as SchemaResult.Failed
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
        server.dispatcher = route(
            "itemTypeFields" to MockResponse().setResponseCode(503),
            "itemTypeCreatorTypes" to ok("[]"),
            "items/new" to ok("[]"),
        )
        val result = runBlocking { client.typeSchema("artwork") } as SchemaResult.Failed
        assertEquals("fields", result.stage)
        assertEquals(503, result.status)
    }

    @Test fun labelComesFromLoadedListWithoutExtraRequest() {
        val router = route(
            "itemTypes" to ok("""[{"itemType":"journalArticle","localized":"Journal Article"}]"""),
            "itemTypeFields" to ok("""[{"field":"title","localized":"Title"}]"""),
            "itemTypeCreatorTypes" to ok("""[{"creatorType":"author","localized":"Author","primary":true}]"""),
            "items/new" to ok("""[{"itemType":"journalArticle","title":"","creators":[]}]"""),
        )
        server.dispatcher = router
        runBlocking { client.itemTypes() }
        val before = router.requests.size
        val schema = (runBlocking { client.typeSchema("journalArticle", "Journal Article") }
            as SchemaResult.Success<TypeSchema>).value
        assertEquals("Journal Article", schema.label)
        // Exactly the three schema requests were made; no fourth itemTypes request for the label.
        assertEquals(before + 3, router.requests.size)
        val newPaths = router.paths.drop(before)
        assertTrue(newPaths.none { it.startsWith("/itemTypes") })
        assertEquals(setOf("/itemTypeFields", "/itemTypeCreatorTypes", "/items/new"),
            newPaths.map { it.substringBefore('?') }.toSet())
    }

    @Test fun requestNeverAnswersHitsTimeoutAndReportsStage() {
        // The server accepts the connection but never answers; the per-step timeout must fire.
        server.dispatcher = neverAnswers()
        val slow = ZoteroSchemaClient.forTesting(OkHttpClient(), server.url("/").toString(), 300, 2000)
        val result = runBlocking { slow.typeSchema("journalArticle", "Journal Article") } as SchemaResult.Failed
        assertEquals("timeout", result.exception)
        assertEquals("fields", result.stage)
    }

    @Test fun failedLoadIsNotCachedAndCanBeRetried() {
        server.dispatcher = route(
            "itemTypeFields" to MockResponse().setResponseCode(500),
            "itemTypeCreatorTypes" to MockResponse().setResponseCode(500),
            "items/new" to MockResponse().setResponseCode(500),
        )
        assertTrue(runBlocking { client.typeSchema("thesis") } is SchemaResult.Failed)
        // A later successful load must work and be returned (nothing was cached as "loading").
        server.dispatcher = route(
            "itemTypeFields" to ok("""[{"field":"title","localized":"Title"},{"field":"university","localized":"University"}]"""),
            "itemTypeCreatorTypes" to ok("""[{"creatorType":"author","localized":"Author","primary":true}]"""),
            "items/new" to ok("""[{"itemType":"thesis","title":"","university":"","creators":[]}]"""),
        )
        val schema = (runBlocking { client.typeSchema("thesis") } as SchemaResult.Success<TypeSchema>).value
        assertTrue(schema.fields.any { it.name == "university" })
    }

    @Test fun basicFormForJournalArticleHasClassicFields() {
        val schema = ZoteroSchemaFallback.schema("journalArticle")
        assertNotNull(schema)
        val names = schema!!.fields.map { it.name }
        assertTrue("title" in names)
        assertTrue("publicationTitle" in names)
        assertTrue("DOI" in names)
        assertTrue("volume" in names)
        assertTrue("author" == schema.primaryCreatorType())
    }

    @Test fun parsingToleratesMissingLocalizedAndExtraFields() {
        // Real API shapes: [{field, localized?}], [{creatorType, localized?, primary?}] and a
        // single JSON object for /items/new. Unknown keys and missing labels must not break it.
        server.dispatcher = route(
            "itemTypeFields" to ok("""[
                {"field":"title","localized":"Title","futureKey":1},
                {"field":"volume"}]"""),
            "itemTypeCreatorTypes" to ok("""[
                {"creatorType":"author","localized":"Author","primary":true,"futureKey":true},
                {"creatorType":"editor"}]"""),
            "items/new" to ok("""{"itemType":"journalArticle","title":"","volume":"",
                "creators":[],"dateAdded":"","futureField":""}"""),
        )
        val schema = (runBlocking { client.typeSchema("journalArticle", "Journal Article") }
            as SchemaResult.Success<TypeSchema>).value
        // The template is authoritative, so an unknown future field is still offered, with a
        // humanised label; hidden bookkeeping keys and "creators" are still dropped.
        assertEquals(listOf("title", "volume", "futureField"), schema.fields.map { it.name })
        assertEquals("Volume", schema.fields.first { it.name == "volume" }.label)
        assertEquals("Future Field", schema.fields.first { it.name == "futureField" }.label)
        assertEquals(listOf("author", "editor"), schema.creatorTypes.map { it.type })
        assertEquals("Editor", schema.creatorTypes.first { it.type == "editor" }.label)
        assertEquals("Journal Article", schema.label)
    }

    @Test fun unreadableTemplateIsReportedNotCached() {
        server.dispatcher = route(
            "itemTypeFields" to ok("[]"),
            "itemTypeCreatorTypes" to ok("[]"),
            "items/new" to ok("not json"),
        )
        val result = runBlocking { client.typeSchema("report") } as SchemaResult.Failed
        assertEquals("template", result.stage)
        assertEquals("malformed", result.exception)
    }

    @Test fun templateIsAuthoritativeOverFieldsList() {
        // The fields endpoint mentions DOI, but the presentation template does not have it.
        server.dispatcher = route(
            "itemTypeFields" to ok("""[
                {"field":"title","localized":"Title"},
                {"field":"meetingName","localized":"Meeting Name"},
                {"field":"DOI","localized":"DOI"}]"""),
            "itemTypeCreatorTypes" to ok("""[{"creatorType":"presenter","localized":"Presenter","primary":true},
                {"creatorType":"contributor","localized":"Contributor"}]"""),
            "items/new" to ok("""[{"itemType":"presentation","title":"","presentationType":"","meetingName":"",
                "place":"","date":"","creators":[]}]"""),
        )
        val schema = (runBlocking { client.typeSchema("presentation") } as SchemaResult.Success<TypeSchema>).value
        assertFalse(schema.fields.any { it.name == "DOI" })
        assertTrue(schema.fields.any { it.name == "meetingName" })
        assertEquals("presenter", schema.primaryCreatorType())
    }
}
