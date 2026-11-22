package org.blaykie.nativeapp.zotero

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.citation.groupsMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZoteroGroupsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ZoteroClient
    private val key = "secret-test-api-key"
    private val twoGroups = """[{"id":555,"version":0,"links":{},"meta":{},"data":{"id":555,"name":"Team Library","owner":42,"type":"Private"}},{"id":777,"version":0,"links":{},"meta":{},"data":{"id":777,"name":"Lab","owner":42,"type":"PublicOpen"}}]"""

    @Before fun setup() {
        ZoteroGroupsCache.clear()
        server = MockWebServer(); server.start()
        val transport = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
        client = ZoteroClient.forTesting(transport) {}
    }
    @After fun teardown() { ZoteroGroupsCache.clear(); server.shutdown() }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test fun realShapedResponseWithTwoGroupsParses() {
        server.enqueue(ok(twoGroups))
        val loaded = loadGroups(client, 42, key) as GroupsLoad.Loaded
        assertEquals(listOf(ZoteroGroup(555, "Team Library"), ZoteroGroup(777, "Lab")), loaded.groups)
        assertEquals("/users/42/groups", server.takeRequest().path)
    }

    @Test fun emptyArrayIsLoadedEmptyAndShowsNoGroupsHint() {
        server.enqueue(ok("[]"))
        val loaded = loadGroups(client, 42, key) as GroupsLoad.Loaded
        assertTrue(loaded.groups.isEmpty())
        assertEquals("No groups found. Groups need read access on your Zotero key.",
            groupsMessage(groupsLoaded = true, groups = emptyList(), groupsError = null))
    }

    @Test fun http403ShowsErrorInsteadOfEmptyHint() {
        server.enqueue(MockResponse().setResponseCode(403))
        val failed = loadGroups(client, 42, key) as GroupsLoad.Failed
        assertEquals("Could not load groups: HTTP 403", failed.message)
        assertEquals("Could not load groups: HTTP 403",
            groupsMessage(groupsLoaded = false, groups = emptyList(), groupsError = failed.message))
    }

    @Test fun failuresAreNotCachedSoNextLoadRetries() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(ZoteroGroupsCache.fetch(client, 42, key) is GroupsLoad.Failed)
        server.enqueue(ok(twoGroups))
        val loaded = ZoteroGroupsCache.fetch(client, 42, key) as GroupsLoad.Loaded
        assertEquals(2, loaded.groups.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun successfulLoadIsCachedAndClearForcesReload() {
        server.enqueue(ok(twoGroups))
        assertEquals(2, (ZoteroGroupsCache.fetch(client, 42, key) as GroupsLoad.Loaded).groups.size)
        assertEquals(2, (ZoteroGroupsCache.fetch(client, 42, key) as GroupsLoad.Loaded).groups.size)
        assertEquals(1, server.requestCount)
        // Clearing (as sign-in, key change and removal do) makes the next load ask again.
        ZoteroGroupsCache.clear()
        server.enqueue(ok("[]"))
        assertTrue((ZoteroGroupsCache.fetch(client, 42, key) as GroupsLoad.Loaded).groups.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun retryReloadsEvenInsideTheCacheWindow() {
        server.enqueue(ok("[]"))
        assertTrue((ZoteroGroupsCache.fetch(client, 42, key) as GroupsLoad.Loaded).groups.isEmpty())
        server.enqueue(ok(twoGroups))
        val loaded = ZoteroGroupsCache.fetch(client, 42, key, force = true) as GroupsLoad.Loaded
        assertEquals(2, loaded.groups.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun noHintWhileLoadingOrWhenGroupsExist() {
        assertNull(groupsMessage(groupsLoaded = false, groups = emptyList(), groupsError = null))
        assertNull(groupsMessage(groupsLoaded = true, groups = listOf(ZoteroGroup(1, "Team")), groupsError = null))
    }
}
