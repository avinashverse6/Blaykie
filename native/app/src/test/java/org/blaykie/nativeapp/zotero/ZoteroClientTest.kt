package org.blaykie.nativeapp.zotero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.docx.CitationFormatting
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZoteroClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroClient
    private val key = "secret-test-api-key"
    private val waits = mutableListOf<Long>()
    private val keyJson = """{"userID":42,"access":{"user":{"library":true,"files":false,"notes":true,"write":false}}}"""
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val transport = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
        client = ZoteroClient.forTesting(transport) { waits += it }
    }
    @After fun teardown() { server.shutdown() }
    private fun response(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun item(n: Int, type: String = "journalArticle") = """{"key":"K$n","data":{"itemType":"$type","title":"Title $n"}}"""
    private fun <T> success(value: ZoteroResult<T>): T = (value as ZoteroResult.Success<T>).value

    @Test fun verifyKeyReturnsPermissionsAndHeaderOnly() {
        server.enqueue(response(keyJson))
        val result = success(client.verifyKey(key))
        assertEquals(42L, result.userId); assertTrue(result.permissions.personalLibrary)
        assertFalse(result.permissions.broaderThanPersonalReadOnly)
        val request = server.takeRequest()
        assertEquals("/keys/current", request.path)
        assertEquals(key, request.getHeader("Zotero-API-Key"))
        assertEquals("3", request.getHeader("Zotero-API-Version"))
        assertFalse(request.path!!.contains(key))
    }
    @Test fun broaderPermissionsAreReportedNotRejected() {
        server.enqueue(response("""{"userID":42,"access":{"user":{"library":true,"write":true},"groups":{"all":{"library":true}}}}"""))
        val permissions = success(client.verifyKey(key)).permissions
        assertTrue(permissions.personalWrite); assertTrue(permissions.groupAccess); assertTrue(permissions.broaderThanPersonalReadOnly)
    }
    @Test fun searchPaginatesAndFiltersAttachmentsNotesAnnotations() {
        server.enqueue(response((1..100).joinToString(",", "[", "]") { item(it, if (it in 1..3) listOf("attachment", "note", "annotation")[it - 1] else "journalArticle") }))
        server.enqueue(response("[${item(101)}]"))
        val items = success(client.search(42, key, "test"))
        assertEquals(98, items.size); assertEquals("K4", items.first().key)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("0", first.requestUrl!!.queryParameter("start")); assertEquals("100", first.requestUrl!!.queryParameter("limit"))
        assertEquals("100", second.requestUrl!!.queryParameter("start")); assertEquals("test", first.requestUrl!!.queryParameter("q"))
        assertEquals(key, first.getHeader("Zotero-API-Key")); assertFalse(first.path!!.contains(key))
    }
    @Test fun searchStopsAtSafeCapEvenIfAllItemsFiltered() {
        repeat(2) { server.enqueue(response((1..100).joinToString(",", "[", "]") { item(it, "note") })) }
        assertSame(ZoteroResult.EmptyLibrary, client.search(42, key, "q")); assertEquals(2, server.requestCount)
    }
    @Test fun fetchItemsBatchesFiftyKeys() {
        server.enqueue(response("[${item(1)}]")); server.enqueue(response("[${item(2)}]"))
        assertEquals(2, success(client.fetchItems(42, key, (1..51).map { "K$it" })).size)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals(50, first.requestUrl!!.queryParameter("itemKey")!!.split(',').size)
        assertEquals("K51", second.requestUrl!!.queryParameter("itemKey"))
    }
    @Test fun invalidKeyAndEmptyLibraryAreExplicit() {
        server.enqueue(MockResponse().setResponseCode(403)); server.enqueue(response("[]"))
        assertSame(ZoteroResult.InvalidKey, client.verifyKey(key)); assertSame(ZoteroResult.EmptyLibrary, client.search(42, key, "q"))
    }
    @Test fun malformedJsonAndNetworkFailureAreExplicit() {
        server.enqueue(response("not json")); assertSame(ZoteroResult.MalformedJson, client.verifyKey(key))
        server.shutdown(); assertSame(ZoteroResult.NetworkFailure, client.verifyKey(key))
    }
    @Test fun retriesAreBoundedAndWaitsAreCapped() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "999")) }
        val result = client.verifyKey(key) as ZoteroResult.RateLimited
        assertEquals(30L, result.retryAfterSeconds); assertEquals(listOf(30000L, 30000L, 30000L), waits)
        assertEquals(4, server.requestCount)
    }
    @Test fun backoffAndRetryAfterHeadersAreHonored() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Backoff", "2"))
        server.enqueue(response(keyJson).setHeader("Retry-After", "3"))
        server.enqueue(response(keyJson))
        assertEquals(42L, success(client.verifyKey(key)).userId)
        assertEquals(42L, success(client.verifyKey(key)).userId)
        assertEquals(listOf(2000L, 3000L), waits)
    }
    @Test fun resultsAndErrorsNeverExposeTheApiKey() {
        server.enqueue(response(keyJson.replace("42", "42,\"key\":\"$key\"")))
        server.enqueue(response("bad $key")); server.enqueue(MockResponse().setResponseCode(403).setBody(key))
        val values = listOf(client.verifyKey(key), client.verifyKey(key), client.verifyKey(key))
        values.forEach { assertFalse(it.toString().contains(key)) }
        assertTrue(values[0] is ZoteroResult.Success); assertSame(ZoteroResult.MalformedJson, values[1]); assertSame(ZoteroResult.InvalidKey, values[2])
        val throwingTransport = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("network failure $key") }.build()
        val failure = ZoteroClient.forTesting(throwingTransport).verifyKey(key)
        assertSame(ZoteroResult.NetworkFailure, failure)
        assertFalse(failure.toString().contains(key))
    }
    @Test fun pythonGoldenFormattingParity() {
        val text = javaClass.classLoader!!.getResourceAsStream("zotero-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val golden = Json.parseToJsonElement(text) as JsonObject
        val items = (golden["items"] as JsonArray).map { element ->
            val obj = element as JsonObject
            ZoteroItem((obj["key"] as JsonPrimitive).content, obj["data"] as JsonObject).citationItem()
        }
        val citations = (golden["citations"] as JsonArray).map { (it as JsonPrimitive).content }
        val references = (golden["references"] as JsonArray).map { (it as JsonPrimitive).content }
        items.forEachIndexed { i, item ->
            assertEquals("citation $i", citations[i], CitationFormatting.inText(listOf(item)))
            assertEquals("reference $i", references[i], CitationFormatting.reference(item).joinToString("") { it.first }.trim())
        }
        assertEquals((golden["combinedCitation"] as JsonPrimitive).content, CitationFormatting.inText(items))
        assertEquals((golden["referenceList"] as JsonPrimitive).content, CitationFormatting.referenceList(items))
    }
}
