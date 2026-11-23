package org.blaykie.nativeapp.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.account.AccountSecret
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.account.SecretCipher
import org.blaykie.nativeapp.zotero.BrowseNode
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.VerifiedKey
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroGroupsCache
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class LibraryControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var controller: LibraryController

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
        override fun removeKey() = Unit
    }

    private val groupsJson = """[{"id":555,"version":0,"links":{},"meta":{"numItems":0},"data":{"id":555,"name":"Blaykie","owner":42,"type":"PublicOpen"}}]"""
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Before fun setup() {
        ZoteroGroupsCache.clear()
        val store = AccountStore(temp.root, TestCipher())
        assertTrue(store.save(AccountSecret("test-key", VerifiedKey(42, KeyPermissions(true, false, false, false, true)))))
        server = MockWebServer(); server.start()
        val transport = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
        controller = LibraryController(store, { ZoteroClient.forTesting(transport) }, CoroutineScope(Dispatchers.Unconfined))
    }
    @After fun teardown() { controller.close(); ZoteroGroupsCache.clear(); server.shutdown() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out: $description (state=${controller.state.value})")
            Thread.sleep(20)
        }
    }
    private fun drainPaths(): List<String> = generateSequence { server.takeRequest(150, TimeUnit.MILLISECONDS) }
        .map { it.path!!.substringBefore('?') }.toList()

    @Test fun groupIsListedAfterGroupsLoaderSucceeds() {
        server.enqueue(ok(groupsJson))
        controller.start(0)
        await("groups loaded") { controller.state.value.groupsLoaded }
        val state = controller.state.value
        assertEquals(listOf("Blaykie"), state.groups.map { it.name })
        assertEquals(0, state.groups.single().numItems) // The empty group is still shown.
        assertNull(state.groupsError)
        assertEquals("/users/42/groups", server.takeRequest().path)
    }

    @Test fun groups403ShowsErrorAndRetryReloads() {
        server.enqueue(MockResponse().setResponseCode(403))
        controller.start(0)
        await("groups error") { controller.state.value.groupsError != null }
        assertEquals("Could not load groups: HTTP 403", controller.state.value.groupsError)
        assertFalse(controller.state.value.groupsLoaded)
        server.enqueue(ok(groupsJson))
        controller.retryGroups()
        await("groups after retry") { controller.state.value.groupsLoaded }
        assertEquals(listOf("Blaykie"), controller.state.value.groups.map { it.name })
    }

    @Test fun backStackGoesNodeRootLibraries() {
        server.enqueue(ok(groupsJson))
        controller.start(0)
        await("groups loaded") { controller.state.value.groupsLoaded }
        server.enqueue(ok("[]")); server.enqueue(ok("[]")) // collections + publications probe
        controller.openLibrary(ZoteroLibrary.Personal)
        await("root loaded") { controller.state.value.collectionsLoaded && controller.state.value.publicationsAvailable }
        assertTrue(controller.state.value.route is LibraryRoute.Root)
        server.enqueue(ok("""[{"key":"K1","data":{"itemType":"journalArticle","title":"Paper","date":"2020"}}]"""))
        controller.openNode(BrowseNode.All, "All Items")
        await("items loaded") { controller.state.value.items.size == 1 }
        controller.selectItem(controller.state.value.items.single())
        assertTrue(controller.state.value.route is LibraryRoute.Detail)
        assertTrue(controller.back()); assertTrue(controller.state.value.route is LibraryRoute.Node)
        assertTrue(controller.back()); assertTrue(controller.state.value.route is LibraryRoute.Root)
        assertTrue(controller.back()); assertTrue(controller.state.value.route is LibraryRoute.Libraries)
        assertFalse(controller.back())
    }

    @Test fun groupLibraryHidesPublications() {
        server.enqueue(ok(groupsJson))
        controller.start(0)
        await("groups loaded") { controller.state.value.groupsLoaded }
        drainPaths()
        server.enqueue(ok("[]")) // collections only; no publications probe for groups
        controller.openLibrary(ZoteroLibrary.Group(controller.state.value.groups.single().let {
            org.blaykie.nativeapp.zotero.ZoteroGroup(it.id, it.name, it.numItems) }))
        await("collections loaded") { controller.state.value.collectionsLoaded }
        assertFalse(controller.state.value.publicationsAvailable)
        val paths = drainPaths()
        assertTrue(paths.any { it == "/groups/555/collections" })
        assertTrue(paths.none { it.contains("publications") })
    }

    @Test fun itemDetailRouteCarriesItem() {
        val item = ZoteroItem("K9", Json.parseToJsonElement("""{"itemType":"book","title":"T","date":"2001"}""") as JsonObject)
        assertEquals("T", (LibraryRoute.Detail(ZoteroLibrary.Personal, BrowseNode.All, "All Items", item)).item.citationItem().title)
    }
}
