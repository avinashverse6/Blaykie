package org.blaykie.nativeapp.session

import java.io.File
import java.io.IOException
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
    @Test fun versionOneSessionRestoresTextEditsAndCanUndoAfterRestart() {
        val store = SessionStore(temp.root)
        val ready = opened(store)
        // Version 1 stores text edits, without later citation-operation fields.
        File(temp.root, "session.json").writeText(
            """{"uri":"content://example/file","fileName":"paper.docx","sourceName":"${ready.session.sourceName}","version":1,"edits":[{"block":0,"text":"alpha BRAVO charlie delta echo"}]}"""
        )
        val restored = SessionStore(temp.root).read() as SessionRead.Ready
        assertEquals(1, restored.session.version)
        assertEquals("alpha BRAVO charlie delta echo", restored.project.blockText(0))
        assertTrue(restored.project.undo())
        assertEquals("alpha bravo charlie delta echo", restored.project.blockText(0))
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
    @Test fun failedUndoSaveLeavesOriginalProjectAndSessionUntouched() {
        val store = SessionStore(temp.root); val ready = opened(store)
        val source = File(temp.root, "session-sources/${ready.session.sourceName}"); val bytes = source.readBytes()
        val changed = "alpha BRAVO charlie delta echo"
        assertTrue(ready.project.applyTextEdit(0, changed))
        val session = ready.session.copy(edits = listOf(EditOperation(0, changed)), draft = Draft(0, changed))
        store.save(session)
        val result = undoTransaction(session, 0, store::read) { throw IOException("simulated save failure") }
        assertSame(UndoResult.Failed, result)
        assertEquals(changed, ready.project.blockText(0)); assertEquals(session.edits, (store.read() as SessionRead.Ready).session.edits)
        assertEquals(session.draft, (store.read() as SessionRead.Ready).session.draft); assertArrayEquals(bytes, source.readBytes())
        val documentSession = session.copy(draft = null); store.save(documentSession)
        assertSame(UndoResult.Failed, undoTransaction(documentSession, null, store::read) { throw IOException("simulated save failure") })
        assertEquals(changed, ready.project.blockText(0)); assertEquals(documentSession.edits, (store.read() as SessionRead.Ready).session.edits)
        store.save(session)
        val success = store.undo(session, 0) as UndoResult.Success
        assertEquals("alpha bravo charlie delta echo", success.project.blockText(0))
        assertEquals(success.project.blockText(0), success.session.draft!!.text)
        assertTrue(success.session.edits.isEmpty()); assertTrue((store.read() as SessionRead.Ready).session.edits.isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }
    @Test fun exportRevisionCountsNewUndoneAndDraftChangesAndSurvivesRestart() {
        val store = SessionStore(temp.root); val ready = opened(store); val p = ready.project
        assertEquals(0, store.unexportedEdits(ready.session, p))
        val firstText = "alpha BRAVO charlie delta echo"
        assertTrue(p.applyTextEdit(0, firstText))
        val first = ready.session.copy(edits = listOf(EditOperation(0, firstText))); store.save(first)
        assertEquals(1, store.unexportedEdits(first, p)); assertTrue(store.recordExport(first))
        assertEquals(0, SessionStore(temp.root).unexportedEdits(first, p))
        val draft = first.copy(draft = Draft(0, "$firstText!"))
        assertEquals(1, store.unexportedEdits(draft, p))
        val undone = store.undo(first) as UndoResult.Success
        assertEquals(1, store.unexportedEdits(undone.session, undone.project))
        val replacement = "ALPHA bravo charlie delta echo"
        assertTrue(undone.project.applyTextEdit(0, replacement))
        val branched = undone.session.copy(edits = listOf(EditOperation(0, replacement)))
        assertEquals(2, store.unexportedEdits(branched, undone.project))
        assertEquals(0, unexportedCount(emptyList(), emptyList()))
        val fresh = opened(store)
        assertEquals(0, store.unexportedEdits(fresh.session, fresh.project))
        store.end(); assertFalse(File(temp.root, "export-revision.json").exists())
    }
    @Test fun failedExportTrackingKeepsEditsUnexported() {
        val store = SessionStore(temp.root); val ready = opened(store)
        val text = "alpha BRAVO charlie delta echo"
        assertTrue(ready.project.applyTextEdit(0, text))
        val session = ready.session.copy(edits = listOf(EditOperation(0, text))); store.save(session)
        File(temp.root, "export-revision.json").mkdir()
        assertFalse(store.recordExport(session))
        assertEquals(1, store.unexportedEdits(session, ready.project))
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
    @Test fun openingAnotherDocumentAndEndingNeverChangesOriginalSource() {
        val original = File(temp.root, "original-user.docx").apply { writeBytes(fixture()) }
        val before = original.readBytes(); val store = SessionStore(temp.root)
        val first = store.open("content://example/original", "original.docx") { target -> original.copyTo(target) }
        val edited = "alpha BRAVO charlie delta echo"
        assertTrue(first.project.applyTextEdit(0, edited))
        store.save(first.session.copy(edits = listOf(EditOperation(0, edited))))
        store.open("content://example/other", "other.docx") { it.writeBytes(fixture()) }
        assertArrayEquals(before, original.readBytes())
        store.end(); assertArrayEquals(before, original.readBytes())
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
