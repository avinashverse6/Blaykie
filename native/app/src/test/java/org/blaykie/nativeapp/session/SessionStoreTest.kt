package org.blaykie.nativeapp.session

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.NotEditable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): ByteArray = javaClass.classLoader!!.getResourceAsStream("edit-parity.docx")!!.use { it.readBytes() }
    private fun opened(store: SessionStore): SessionRead.Ready = store.open("content://example/file", "paper.docx") { it.writeBytes(fixture()) }
    private fun golden(): JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("edit-parity.json")!!.bufferedReader().use { it.readText() }) as JsonObject
    private fun value(json: JsonObject, key: String) = (json[key] as JsonPrimitive).content

    @Test fun pythonGoldenSingleMultiReplayProtectedAndUndo() {
        val expected = golden()
        val ready = opened(SessionStore(temp.root)); val p = ready.project
        assertTrue(p.applyTextEdit(0, "alpha BRAVO charlie delta echo"))
        assertEquals(value(expected, "single"), p.blockText(0))
        assertEquals((expected["singleSummary"] as JsonArray).map { (it as JsonPrimitive).content.toInt() }, p.editSummary().toList())
        assertTrue(p.undo())
        assertTrue(p.applyTextEdit(0, "ALPHA bravo charlie delta ECHO"))
        assertEquals(value(expected, "multi"), p.blockText(0))
        assertEquals((expected["multiSummary"] as JsonArray).map { (it as JsonPrimitive).content.toInt() }, p.editSummary().toList())
        val session = ready.session.copy(edits = listOf(EditOperation(0, p.blockText(0))))
        val store = SessionStore(temp.root); store.save(session)
        val restored = store.read() as SessionRead.Ready
        assertEquals(value(expected, "replay"), restored.project.blockText(0))
        assertEquals(value(expected, "undo").toBoolean(), restored.project.undo())
        assertEquals(value(expected, "afterUndo"), restored.project.blockText(0))
        val protected = try { p.applyTextEdit(1, "before broken after"); "accepted" } catch (_: NotEditable) { "rejected" }
        assertEquals(value(expected, "protected"), protected)
        assertEquals(value(expected, "protectedAfter"), p.blockText(1))
    }
    @Test fun savesDraftAndReplaysUndoHistory() {
        val store = SessionStore(temp.root); val ready = opened(store)
        ready.project.applyTextEdit(0, "alpha BRAVO charlie delta echo")
        store.save(ready.session.copy(edits = listOf(EditOperation(0, ready.project.blockText(0))), draft = Draft(0, "unfinished draft")))
        val restored = store.read() as SessionRead.Ready
        assertEquals("unfinished draft", restored.session.draft!!.text)
        assertEquals("alpha BRAVO charlie delta echo", restored.project.blockText(0))
        assertTrue(restored.project.undo()); assertEquals("alpha bravo charlie delta echo", restored.project.blockText(0))
    }
    @Test fun corruptSessionAndMissingSourceAreInvalid() {
        val store = SessionStore(temp.root); val ready = opened(store)
        File(temp.root, "session-sources/${ready.session.sourceName}").delete()
        assertSame(SessionRead.Invalid, store.read())
        File(temp.root, "session.json").writeText("broken")
        assertSame(SessionRead.Invalid, store.read())
        store.end(); assertSame(SessionRead.Missing, store.read())
    }
    @Test fun newDocumentAndEndRemovePrivateCopies() {
        val store = SessionStore(temp.root); val first = opened(store); val second = opened(store)
        assertFalse(File(temp.root, "session-sources/${first.session.sourceName}").exists())
        assertTrue(File(temp.root, "session-sources/${second.session.sourceName}").exists())
        store.end(); assertFalse(File(temp.root, "session-sources/${second.session.sourceName}").exists())
    }
    @Test fun backStackClosesEditorThenDocumentThenExits() {
        assertEquals(Screen.DOCUMENT, backTarget(Screen.EDITOR)); assertEquals(Screen.HOME, backTarget(Screen.DOCUMENT))
        assertNull(backTarget(Screen.HOME)); assertEquals(Screen.SETTINGS, backTarget(Screen.LAST_CRASH))
    }
    @Test fun crashLogExcludesMessagesAndCauses() {
        val error = IllegalStateException("private document text and API-KEY-SECRET", IllegalArgumentException("API-KEY-SECRET"))
        error.stackTrace = arrayOf(StackTraceElement("org.blaykie.nativeapp.DocxProject", "API-KEY-SECRET", "private document text", 12))
        val log = CrashLog.sanitized(error)
        assertTrue(log.contains("java.lang.IllegalStateException")); assertTrue(log.contains("at DocxProject:12"))
        assertFalse(log.contains("private document text")); assertFalse(log.contains("API-KEY-SECRET"))
        assertFalse(log.contains("IllegalArgumentException"))
    }
}
