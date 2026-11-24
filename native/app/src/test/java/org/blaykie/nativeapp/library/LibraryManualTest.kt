package org.blaykie.nativeapp.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.account.AccountSecret
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.account.SecretCipher
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.VerifiedKey
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroGroupsCache
import org.blaykie.nativeapp.zotero.ZoteroSchemaClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class LibraryManualTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var controller: LibraryController

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
        override fun removeKey() = Unit
    }

    private val created = """{"successful":{"0":{"key":"NEWKEY12","data":{"key":"NEWKEY12"}}},"failed":{},"unchanged":{}}"""
    private val noIdentifier = ItemDraft("journalArticle", linkedMapOf("title" to "Typed by hand"), mutableListOf())
    private val withIsbn = ItemDraft("book",
        linkedMapOf("title" to "A Book", "ISBN" to "978-1-4028-9462-6"), mutableListOf())

    @Before fun setup() {
        ZoteroGroupsCache.clear()
        val store = AccountStore(temp.root, TestCipher())
        assertTrue(store.save(AccountSecret("test-key",
            VerifiedKey(42, KeyPermissions(true, false, false, false, true)), username = "ada")))
        server = MockWebServer(); server.start()
        val transport = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
        controller = LibraryController(store, { ZoteroClient.forTesting(transport) }, CoroutineScope(Dispatchers.Unconfined),
            schemaFactory = { ZoteroSchemaClient.forTesting(transport, server.url("/").toString(), 300, 2000) })
    }
    @After fun teardown() { controller.close(); ZoteroGroupsCache.clear(); server.shutdown() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out: $description")
            Thread.sleep(20)
        }
    }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private val journalArticleTypes = """[{"itemType":"journalArticle","localized":"Journal Article"},
        {"itemType":"book","localized":"Book"}]"""
    private fun loadTypes() {
        server.enqueue(ok(journalArticleTypes))
        controller.loadManualTypes()
        await("types") { controller.state.value.manual.types.isNotEmpty() }
    }
    private fun journalArticleFields() = ok("""[{"field":"title","localized":"Title"},
        {"field":"publicationTitle","localized":"Publication"},{"field":"DOI","localized":"DOI"}]""")
    private fun journalArticleCreators() = ok("""[{"creatorType":"author","localized":"Author","primary":true},
        {"creatorType":"editor","localized":"Editor"}]""")
    private fun journalArticleTemplate() = ok("""[{"itemType":"journalArticle","title":"","publicationTitle":"",
        "DOI":"","dateAdded":"","dateModified":"","creators":[],"tags":[],"collections":[],"relations":{}}]""")

    @Test fun duplicateCheckIsSkippedWithoutDoiOrIsbn() {
        server.enqueue(ok(created))
        controller.saveManualDraft(noIdentifier, inCollection = false)
        await("saved") { controller.state.value.manual.savedKey == "NEWKEY12" }
        assertEquals(1, server.requestCount) // only the POST; no search first
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun duplicateCheckRunsWhenIsbnIsEntered() {
        server.enqueue(ok("""[{"key":"OLD1","data":{"itemType":"book","title":"The Existing Book","ISBN":"9781402894626"}}]"""))
        server.enqueue(ok(created))
        controller.saveManualDraft(withIsbn, inCollection = false)
        await("duplicate reported") { controller.state.value.manual.duplicateTitle != null }
        assertEquals("The Existing Book", controller.state.value.manual.duplicateTitle)
        assertTrue(controller.state.value.manual.duplicateViaIsbn)
        assertEquals(1, server.requestCount) // the POST has not been sent yet
        controller.confirmManualAddAnyway(withIsbn, inCollection = false)
        await("saved after Add anyway") { controller.state.value.manual.savedKey == "NEWKEY12" }
        assertEquals(2, server.requestCount)
    }

    @Test fun writeTokenIsReusedWhenRetryingAfterAnError() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok(created))
        controller.saveManualDraft(noIdentifier, inCollection = false)
        await("error shown") { controller.state.value.manual.error != null }
        assertTrue(controller.state.value.manual.error!!.contains("500"))
        controller.saveManualDraft(noIdentifier, inCollection = false)
        await("saved on Retry") { controller.state.value.manual.savedKey == "NEWKEY12" }
        val tokens = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }
            .filter { it.method == "POST" }.map { it.getHeader("Zotero-Write-Token") }.toList()
        assertEquals(2, tokens.size)
        assertNotNull(tokens[0])
        assertEquals(32, tokens[0]!!.length)
        assertEquals(tokens[0], tokens[1])
    }

    @Test fun saveForbiddenShowsPermissionSheet() {
        server.enqueue(MockResponse().setResponseCode(403))
        controller.saveManualDraft(noIdentifier, inCollection = false)
        await("permission sheet") { controller.state.value.showPermissionSheet }
        assertFalse(controller.state.value.manual.saving)
        assertNull(controller.state.value.manual.savedKey)
    }

    @Test fun savedManualItemIsKeptForTheViewAction() {
        server.enqueue(ok(created))
        controller.saveManualDraft(noIdentifier, inCollection = false)
        await("saved") { controller.state.value.manual.savedKey == "NEWKEY12" }
        assertEquals("NEWKEY12", controller.state.value.manual.savedItem?.key)
        assertNull(controller.state.value.manual.error)
        assertNull(controller.state.value.manual.duplicateTitle)
    }

    @Test fun parallelSchemaLoadShowsTheForm() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("itemTypes") -> ok(journalArticleTypes)
                request.path.orEmpty().contains("itemTypeFields") -> journalArticleFields()
                request.path.orEmpty().contains("itemTypeCreatorTypes") -> journalArticleCreators()
                request.path.orEmpty().contains("items/new") -> journalArticleTemplate()
                else -> MockResponse().setResponseCode(404)
            }
        }
        loadTypes()
        controller.loadManualSchema("journalArticle")
        await("form loaded") { controller.state.value.manual.schemas.containsKey("journalArticle") }
        assertNull(controller.state.value.manual.schemaError)
        val schema = controller.state.value.manual.schemas["journalArticle"]
        assertNotNull(schema)
        assertTrue(schema!!.fields.any { it.name == "title" })
        assertTrue(schema.creatorTypes.map { it.type }.contains("author"))
    }

    @Test fun schema403ShowsStageStatusRetryAndUseBasic() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("itemTypes") -> ok(journalArticleTypes)
                request.path.orEmpty().contains("itemTypeFields") -> MockResponse().setResponseCode(403)
                request.path.orEmpty().contains("itemTypeCreatorTypes") -> journalArticleCreators()
                request.path.orEmpty().contains("items/new") -> journalArticleTemplate()
                else -> MockResponse().setResponseCode(404)
            }
        }
        loadTypes()
        controller.loadManualSchema("journalArticle")
        await("error shown") { controller.state.value.manual.schemaError != null }
        assertEquals("fields", controller.state.value.manual.schemaErrorStage)
        assertEquals(403, controller.state.value.manual.schemaErrorStatus)
        assertTrue(controller.state.value.manual.schemaError!!.contains("HTTP 403"))
        // A failed load is not cached, so Retry actually reloads.
        assertFalse(controller.state.value.manual.schemas.containsKey("journalArticle"))
        // Retry: now the fields request succeeds too.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("itemTypes") -> ok(journalArticleTypes)
                request.path.orEmpty().contains("itemTypeFields") -> journalArticleFields()
                request.path.orEmpty().contains("itemTypeCreatorTypes") -> journalArticleCreators()
                request.path.orEmpty().contains("items/new") -> journalArticleTemplate()
                else -> MockResponse().setResponseCode(404)
            }
        }
        controller.loadManualSchema("journalArticle")
        await("reloaded after Retry") { controller.state.value.manual.schemas.containsKey("journalArticle") }
        assertNull(controller.state.value.manual.schemaError)
        // Use basic form opens the form with classic fields so the user can always continue.
        controller.useBasicManualForm("journalArticle")
        val basic = controller.state.value.manual.schemas["journalArticle"]
        assertNotNull(basic)
        assertTrue(basic!!.fields.any { it.name == "title" })
        assertTrue(basic.basic)
    }

    @Test fun schemaTimeoutShowsMessageAndDoesNotSpinForever() {
        // The server accepts the connection but never answers; the per-step timeout must trip.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(3000)
                return ok("[]")
            }
        }
        loadTypes()
        controller.loadManualSchema("journalArticle")
        await("timeout reported") { controller.state.value.manual.schemaError != null }
        assertEquals("timeout", controller.state.value.manual.schemaErrorException)
        // The sheet must show an error (not an endless spinner).
        assertNull(controller.state.value.manual.schemas["journalArticle"])
        assertTrue(controller.state.value.manual.schemaError!!.contains("timeout"))
    }
}
