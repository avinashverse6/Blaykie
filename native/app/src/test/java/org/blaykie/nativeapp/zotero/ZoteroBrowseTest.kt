package org.blaykie.nativeapp.zotero

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.library.libraryError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZoteroBrowseTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroClient
    private val key = "secret-test-api-key"

    @Before fun setup() {
        server = MockWebServer(); server.start()
        val transport = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
        client = ZoteroClient.forTesting(transport) {}
    }
    @After fun teardown() { server.shutdown() }
    private fun ok(body: String, total: Int? = null) = MockResponse().setResponseCode(200).setBody(body)
        .also { if (total != null) it.addHeader("Total-Results", total.toString()) }
    private fun item(key: String, title: String) =
        """{"key":"$key","meta":{"numChildren":2},"data":{"itemType":"journalArticle","title":"$title","date":"2020","creators":[{"creatorType":"author","firstName":"Ada","lastName":"Lovelace"}]}}"""
    private fun <T> success(value: ZoteroResult<T>): T = (value as ZoteroResult.Success<T>).value

    @Test fun collectionsTreeBuildsFromFlatParentResponse() {
        server.enqueue(ok("""[
          {"key":"C2","meta":{"numItems":3},"data":{"key":"C2","name":"Chapter drafts","parentCollection":"C1"}},
          {"key":"C1","meta":{"numItems":10},"data":{"key":"C1","name":"Thesis","parentCollection":false}},
          {"key":"C3","meta":{"numItems":1},"data":{"key":"C3","name":"Loose"}}
        ]"""))
        val collections = success(client.collections(ZoteroLibrary.Personal, 42, key))
        assertEquals(3, collections.size)
        assertEquals("/users/42/collections", server.takeRequest().path!!.substringBefore('?'))
        val flat = flattenTree(collectionTree(collections))
        assertEquals(listOf("Loose" to 0, "Thesis" to 0, "Chapter drafts" to 1), flat.map { it.first.name to it.second })
        assertEquals(10, collections.first { it.key == "C1" }.numItems)
        assertEquals("C1", collections.first { it.key == "C2" }.parentKey)
        assertNull(collections.first { it.key == "C3" }.parentKey)
    }

    @Test fun browsePagingSendsStartAndReadsTotalResults() {
        server.enqueue(ok("[${item("K1", "One")}]", total = 60))
        val page = success(client.browsePage(ZoteroLibrary.Personal, 42, key, BrowseQuery(start = 25, limit = 25)))
        assertEquals(60, page.totalResults)
        assertEquals(1, page.rawCount)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/users/42/items/top"))
        assertEquals("25", request.requestUrl!!.queryParameter("start"))
        assertEquals("25", request.requestUrl!!.queryParameter("limit"))
        assertTrue(hasMorePages(60, fetchedRaw = 25, lastRawCount = 25, limit = 25))
        assertFalse(hasMorePages(60, fetchedRaw = 60, lastRawCount = 10, limit = 25))
    }

    @Test fun browseSendsSearchSortAndTagParameters() {
        server.enqueue(ok("[]", total = 0))
        success(client.browsePage(ZoteroLibrary.Group(ZoteroGroup(555, "Team")), 42, key,
            BrowseQuery(collectionKey = "C1", text = "climate", everything = false, tag = "energy",
                sort = BrowseSort.Title, descending = false, start = 0)))
        val url = server.takeRequest().requestUrl!!
        assertTrue(url.encodedPath.startsWith("/groups/555/collections/C1/items/top"))
        assertEquals("climate", url.queryParameter("q"))
        assertEquals("titleCreatorYear", url.queryParameter("qmode"))
        assertEquals("energy", url.queryParameter("tag"))
        assertEquals("title", url.queryParameter("sort"))
        assertEquals("asc", url.queryParameter("direction"))

        server.enqueue(ok("[]", total = 0))
        success(client.browsePage(ZoteroLibrary.Personal, 42, key, BrowseQuery(text = "climate", everything = true)))
        assertEquals("everything", server.takeRequest().requestUrl!!.queryParameter("qmode"))
    }

    @Test fun tagsPageParsesNamesCountsAndTotal() {
        server.enqueue(ok("""[{"tag":"energy","meta":{"numItems":4}},{"tag":"history","meta":{"numItems":2}}]""", total = 2))
        val page = success(client.tagsPage(ZoteroLibrary.Personal, 42, key, "", 0))
        assertEquals(listOf(ZoteroTag("energy", 4), ZoteroTag("history", 2)), page.tags)
        assertEquals(2, page.totalResults)
        assertTrue(server.takeRequest().path!!.startsWith("/users/42/tags"))
    }

    @Test fun httpErrorKeepsStatusForFriendlyMessage() {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client.browsePage(ZoteroLibrary.Personal, 42, key, BrowseQuery())
        assertTrue(result is ZoteroResult.InvalidKey)
        assertEquals("Could not load items: HTTP 403", libraryError("Could not load items", result))
        server.enqueue(MockResponse().setResponseCode(500))
        val failed = client.collections(ZoteroLibrary.Personal, 42, key)
        assertEquals("Could not load collections: HTTP 500", libraryError("Could not load collections", failed))
    }

    @Test fun pageCacheIsMemoryOnlyAndClearable() {
        val cache = org.blaykie.nativeapp.library.LibraryCache(ttlMillis = 1000)
        val entry = org.blaykie.nativeapp.library.LibraryCache.Entry(emptyList(), 0, 0, exhausted = true, atMillis = 100)
        cache.put("k", entry)
        assertEquals(entry, cache.get("k", 500))
        assertNull(cache.get("k", 2000)) // expired
        cache.put("k", entry)
        cache.clear()
        assertNull(cache.get("k", 500))
        assertEquals(0, cache.size())
    }
}
