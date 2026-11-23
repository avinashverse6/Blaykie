package org.blaykie.nativeapp.zotero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.library.newWriteToken
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZoteroWriteTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroClient
    private val key = "secret-test-api-key"
    private val waits = mutableListOf<Long>()
    private val itemJson = """[{"itemType":"journalArticle","title":"Paper","DOI":"10.1000/xyz123","creators":[{"creatorType":"author","firstName":"Ada","lastName":"Lovelace"}],"collections":["COLL1"]}]"""
    private val created = """{"successful":{"0":{"key":"NEWKEY12","data":{"key":"NEWKEY12","itemType":"journalArticle","title":"Paper"}}},"failed":{},"unchanged":{}}"""

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

    @Test fun createItemSendsBodyHeadersAndParsesNewKey() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(created))
        val token = newWriteToken()
        assertEquals(32, token.length); assertTrue(token.all { it.isLetterOrDigit() })
        val result = client.createItem(42, key, itemJson, token)
        assertEquals("NEWKEY12", (result as ZoteroClient.WriteResult.Success).itemKey)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/users/42/items", request.path)
        assertEquals(key, request.getHeader("Zotero-API-Key"))
        assertEquals("3", request.getHeader("Zotero-API-Version"))
        assertEquals("application/json", request.getHeader("Content-Type")!!.substringBefore(';'))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Blaykie/"))
        assertEquals(token, request.getHeader("Zotero-Write-Token"))
        assertFalse(request.path!!.contains(key))
        val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonArray
        assertEquals(1, body.size)
        val item = body[0].jsonObject
        assertEquals("\"journalArticle\"", item["itemType"].toString())
        assertEquals("\"COLL1\"", (item["collections"] as JsonArray)[0].toString())
    }

    @Test fun sameWriteTokenIsSentOnRetry() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(created))
        server.enqueue(MockResponse().setResponseCode(200).setBody(created))
        val token = newWriteToken()
        client.createItem(42, key, itemJson, token)
        client.createItem(42, key, itemJson, token) // a retry of the same save reuses the token
        assertEquals(token, server.takeRequest().getHeader("Zotero-Write-Token"))
        assertEquals(token, server.takeRequest().getHeader("Zotero-Write-Token"))
    }

    @Test fun forbiddenPreconditionAndTooLargeAreDistinct() {
        server.enqueue(MockResponse().setResponseCode(403))
        val forbidden = client.createItem(42, key, itemJson, newWriteToken())
        assertSame(ZoteroClient.WriteResult.Forbidden, forbidden)
        assertFalse(forbidden.toString().contains(key))
        server.enqueue(MockResponse().setResponseCode(412))
        assertSame(ZoteroClient.WriteResult.PreconditionFailed, client.createItem(42, key, itemJson, newWriteToken()))
        server.enqueue(MockResponse().setResponseCode(413))
        assertSame(ZoteroClient.WriteResult.TooLarge, client.createItem(42, key, itemJson, newWriteToken()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ZoteroClient.WriteResult.HttpError(500), client.createItem(42, key, itemJson, newWriteToken()))
    }

    @Test fun rateLimitHonoursBackoffThenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Backoff", "7"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(created))
        val result = client.createItem(42, key, itemJson, newWriteToken())
        assertTrue(result is ZoteroClient.WriteResult.Success)
        assertEquals(listOf(7000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test fun findByDoiMatchesCaseInsensitively() {
        server.enqueue(MockResponse().setBody("""[
            {"key":"OTHER","data":{"itemType":"journalArticle","title":"Other","DOI":"10.9999/other"}},
            {"key":"MATCH1","data":{"itemType":"journalArticle","title":"The Paper","DOI":"10.1000/XYZ123"}}]"""))
        val found = (client.findByDoi(42, key, "10.1000/xyz123") as ZoteroResult.Success).value
        assertEquals(listOf("MATCH1"), found.map { it.key })
        val url = server.takeRequest().requestUrl!!
        assertEquals("10.1000/xyz123", url.queryParameter("q"))
        assertEquals("everything", url.queryParameter("qmode"))
        assertEquals("5", url.queryParameter("limit"))
    }
}
