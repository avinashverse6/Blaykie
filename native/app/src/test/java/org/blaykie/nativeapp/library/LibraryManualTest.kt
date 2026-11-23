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
}
