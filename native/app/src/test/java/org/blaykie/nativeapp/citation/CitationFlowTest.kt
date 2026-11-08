package org.blaykie.nativeapp.citation

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.session.EditOperation
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CitationFlowTest {
    @get:Rule val temp = TemporaryFolder()
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
    private fun golden(): JsonObject = Json.parseToJsonElement(resource("citation-options-golden.json").toString(Charsets.UTF_8)) as JsonObject
    private fun str(obj: JsonObject, name: String) = (obj[name] as JsonPrimitive).content
    private fun zotero(value: kotlinx.serialization.json.JsonElement): ZoteroItem {
        val obj = value as JsonObject
        return ZoteroItem(str(obj, "key"), obj["data"] as JsonObject)
    }
    private fun items(case: JsonObject): List<CitationItem> = (case["items"] as JsonArray).map { zotero(it).citationItem() }
    private fun open(store: SessionStore): SessionRead.Ready = store.open("content://document", "parity.docx") { it.writeBytes(resource("parity.docx")) }
    @Test fun pythonGoldenParentheticalNarrativePagePrefixAndUnicode() {
        for (value in golden()["cases"] as JsonArray) {
            val case = value as JsonObject
            val actual = CitationFormatting.citationText(items(case), str(case, "style"), str(case, "page"), str(case, "prefix"))
            assertEquals(str(case, "name"), str(case, "expected"), actual)
        }
    }
    @Test fun insertionAtCursorOrAfterSelectionMatchesPythonAndUndo() {
        val store = SessionStore(temp.root); val ready = open(store); val p = ready.project
        val selection = golden()["selection"] as JsonObject
        val item = items((golden()["cases"] as JsonArray)[0] as JsonObject).single()
        val start = str(selection, "start").toInt(); val end = str(selection, "end").toInt()
        assertEquals(end, insertionPoint(start, end, p.blockText(1).length))
        assertEquals(end, insertionPoint(end, start, p.blockText(1).length))
        assertEquals(start, insertionPoint(start, start, p.blockText(1).length))
        p.insertCitation(1, end, end, listOf(item))
        assertEquals(str(selection, "after"), p.blockText(1))
        assertEquals(str(selection, "citation"), p.citations().last().text)
        assertTrue(p.undo()); assertEquals(str(selection, "before"), p.blockText(1)); assertFalse(p.undo())
        p.insertCitation(1, start, start, listOf(item))
        assertTrue(p.blockText(1).contains("(Lovelace, 2020)")); assertTrue(p.undo())
        assertEquals(str(selection, "before"), p.blockText(1))
    }
    @Test fun utf16CursorAfterSupplementaryCharacterMatchesPythonCodepointGolden() {
        val project = open(SessionStore(temp.root)).project
        val fixture = golden()["supplementary"] as JsonObject
        val before = str(fixture, "before")
        assertTrue(project.applyTextEdit(1, before))
        val cursor = before.offsetByCodePoints(0, str(fixture, "codepoint_position").toInt())
        assertEquals(4, cursor)
        val item = items((golden()["cases"] as JsonArray)[0] as JsonObject).single()
        project.insertCitation(1, cursor, cursor, listOf(item))
        assertEquals(str(fixture, "after"), project.blockText(1))
        assertTrue(project.undo()); assertEquals(before, project.blockText(1))
    }
    @Test fun severalItemsDeduplicateAndReplayAsOneOperation() {
        val case = (golden()["cases"] as JsonArray)[4] as JsonObject
        val chosen = items(case)
        val store = SessionStore(temp.root); val ready = open(store); val p = ready.project
        val position = p.blockText(1).length
        p.insertCitation(1, position, position, chosen, "parenthetical", "15", "see")
        assertEquals(str(case, "expected"), p.citations().last().text)
        assertEquals(setOf("ONE1", "TWO2"), p.presentKeys())
        assertEquals(2, p.citations().last().keys.size)
        val edited = p.blockText(1)
        val op = EditOperation(block = 1, type = "citation", position = position, items = chosen, page = "15", prefix = "see")
        store.save(ready.session.copy(edits = listOf(op)))
        val replay = store.read() as SessionRead.Ready
        assertEquals(2, replay.session.version); assertEquals(edited, replay.project.blockText(1))
        assertEquals(setOf("ONE1", "TWO2"), replay.project.presentKeys())
        assertTrue(replay.project.undo()); assertEquals("First paragraph about citations.", replay.project.blockText(1))
        assertFalse(replay.project.undo())
    }
    @Test fun alreadyCitedItemDoesNotDuplicatePresentKeys() {
        val store = SessionStore(temp.root); val project = open(store).project
        val item = items((golden()["cases"] as JsonArray)[0] as JsonObject).single()
        val first = project.blockText(1).length
        project.insertCitation(1, first, first, listOf(item))
        val second = project.blockText(2).length
        project.insertCitation(2, second, second, listOf(item))
        assertEquals(setOf("ONE1"), project.presentKeys()); assertEquals(2, project.citations().size)
        assertTrue(project.undo()); assertEquals(setOf("ONE1"), project.presentKeys())
    }
    @Test fun loadsOldSessionWithoutVersionOrOperationType() {
        val store = SessionStore(temp.root); val ready = open(store)
        File(temp.root, "session.json").writeText("""{"uri":"content://document","fileName":"parity.docx","sourceName":"${ready.session.sourceName}","edits":[{"block":1,"text":"Changed paragraph"}]}""")
        val restored = store.read() as SessionRead.Ready
        assertEquals(1, restored.session.version); assertEquals("Changed paragraph", restored.project.blockText(1))
        assertTrue(restored.project.undo()); assertEquals("First paragraph about citations.", restored.project.blockText(1))
    }
    @Test fun selectionDeduplicatesKeysAndMapsErrorsAndNoKey() {
        val values = (golden()["cases"] as JsonArray)[4] as JsonObject
        val results = (values["items"] as JsonArray).map(::zotero)
        assertEquals(2, chosenItems(results, setOf(0, 1, 2)).size)
        assertFalse(hasUsableKey(AccountRead.Missing)); assertFalse(hasUsableKey(AccountRead.Unavailable))
        assertTrue(searchMessage(ZoteroResult.InvalidKey).contains("Invalid"))
        assertTrue(searchMessage(ZoteroResult.NetworkFailure).contains("Network"))
        assertTrue(searchMessage(ZoteroResult.RateLimited(5)).contains("rate limit"))
        assertTrue(searchMessage(ZoteroResult.MalformedJson).contains("invalid data"))
        assertEquals("No references found.", searchMessage(ZoteroResult.EmptyLibrary))
        assertEquals("", searchMessage(ZoteroResult.Cancelled))
    }
    @Test fun cancellingSearchCancelsTheActiveOkHttpCallWithoutAnError() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build())
            }.build()
            val client = ZoteroClient.forTesting(transport)
            val result = AtomicReference<ZoteroResult<List<ZoteroItem>>>()
            val worker = Thread { result.set(client.search(123, "private-key", "query")) }
            worker.start(); assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            client.cancel(); worker.join(5000)
            assertFalse(worker.isAlive); assertSame(ZoteroResult.Cancelled, result.get()); assertEquals("", searchMessage(result.get()))
        } finally { server.shutdown() }
    }
}
