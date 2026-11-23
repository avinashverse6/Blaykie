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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class LibraryAddTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var controller: LibraryController

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
        override fun removeKey() = Unit
    }

    private val created = """{"successful":{"0":{"key":"NEWKEY12","data":{"key":"NEWKEY12"}}},"failed":{},"unchanged":{}}"""
    private val draft = ItemDraft("journalArticle",
        linkedMapOf("title" to "Paper", "DOI" to "10.1000/xyz123"),
        mutableListOf())

    @Before fun setup() {
        ZoteroGroupsCache.clear()
        val store = AccountStore(temp.root, TestCipher())
        assertTrue(store.save(AccountSecret("test-key",
            VerifiedKey(42, KeyPermissions(true, false, false, false, false)), username = "ada")))
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
            if (System.currentTimeMillis() > deadline) fail("Timed out: $description")
            Thread.sleep(20)
        }
    }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test fun permissionDecisionFollowsKeyAccess() {
        assertEquals(AddPermission.Allowed, addPermission(signedIn = true, writeAccess = true, isOAuth = true))
        assertEquals(AddPermission.NeedsOAuth, addPermission(signedIn = true, writeAccess = false, isOAuth = true))
        assertEquals(AddPermission.NeedsManualKey, addPermission(signedIn = true, writeAccess = false, isOAuth = false))
        assertEquals(AddPermission.SignedOut, addPermission(signedIn = false, writeAccess = false, isOAuth = false))
    }

    @Test fun saveForbiddenShowsPermissionSheet() {
        server.enqueue(ok("[]")) // duplicate check
        server.enqueue(MockResponse().setResponseCode(403)) // create rejected
        controller.saveDraft(draft, inCollection = false)
        await("permission sheet") { controller.state.value.showPermissionSheet }
        assertFalse(controller.state.value.add.saving)
        assertNull(controller.state.value.add.savedKey)
    }

    @Test fun save412RetriesOnceWithSameTokenThenSucceeds() {
        server.enqueue(ok("[]")) // duplicate check
        server.enqueue(MockResponse().setResponseCode(412)) // first create
        server.enqueue(ok("[]")) // re-fetch after 412
        server.enqueue(ok(created)) // retried create
        controller.saveDraft(draft, inCollection = false)
        await("saved") { controller.state.value.add.savedKey == "NEWKEY12" }
        val tokens = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }
            .filter { it.method == "POST" }.map { it.getHeader("Zotero-Write-Token") }.toList()
        assertEquals(2, tokens.size)
        assertNotNull(tokens[0]); assertEquals(tokens[0], tokens[1])
    }

    @Test fun duplicateIsReportedBeforeSaving() {
        server.enqueue(ok("""[{"key":"OLD1","data":{"itemType":"journalArticle","title":"The Existing Paper","DOI":"10.1000/XYZ123"}}]"""))
        controller.saveDraft(draft, inCollection = false)
        await("duplicate reported") { controller.state.value.add.duplicateTitle != null }
        assertEquals("The Existing Paper", controller.state.value.add.duplicateTitle)
        assertNull(controller.state.value.add.savedKey)
        assertEquals(1, server.requestCount) // no POST was sent
    }
}
