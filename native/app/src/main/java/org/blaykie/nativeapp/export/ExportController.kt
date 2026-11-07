package org.blaykie.nativeapp.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.citation.searchMessage
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroResult

internal enum class ExportPhase { IDLE, PREPARING, CHOOSE, WAITING, WRITING, DONE }
internal data class ExportState(
    val phase: ExportPhase = ExportPhase.IDLE, val line: String = "match", val name: String = "",
    val message: String = "", val needsKey: Boolean = false, val omitted: Int = 0,
    val shareFile: File? = null,
) { override fun toString() = "ExportState(redacted)" }
private data class Prepared(val project: DocxProject, val sourceUri: String, val name: String, val omitted: Int, val oldBibliographyRetained: Boolean)
internal class ExportController(private val context: Context, private val sessions: SessionStore, private val accounts: AccountStore) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = mutable
    @Volatile private var client: ZoteroClient? = null
    @Volatile private var cancelled = false
    private var prepared: Prepared? = null
    fun setLine(line: String) { if (mutable.value.phase in setOf(ExportPhase.IDLE, ExportPhase.DONE)) mutable.value = mutable.value.copy(line = line) }
    fun begin(session: Session) {
        if (!mayStartExport(mutable.value.phase)) return
        cancelled = false; prepared = null
        val line = mutable.value.line
        mutable.value = ExportState(phase = ExportPhase.PREPARING, line = line, name = exportName(session.fileName), message = "Preparing export…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var stage = FailureStage.PRIVATE
                try {
                    cleanExportCache(context.cacheDir)
                    stage = FailureStage.SOURCE
                    context.contentResolver.openInputStream(Uri.parse(session.uri))?.use { } ?: throw FileNotFoundException()
                    val read = sessions.read() as? SessionRead.Ready ?: throw FileNotFoundException()
                    if (read.session.sourceName != session.sourceName) throw FileNotFoundException()
                    val project = read.project
                    if (cancelled) return@withContext Pair(null, "Lookup cancelled.")
                    var omitted = 0
                    if (project.missingKeys().isNotEmpty()) {
                        val account = accounts.read() as? AccountRead.Present
                            ?: return@withContext Pair(null, "Some earlier citations need their references. Add your Zotero details first.")
                        val lookup = ZoteroClient(); client = lookup
                        if (cancelled) lookup.cancel()
                        when (val response = recoverReferences(project, lookup, account.account.verified.userId, account.account.key)) {
                            is ZoteroResult.Success -> omitted = response.value
                            is ZoteroResult.Cancelled -> return@withContext Pair(null, "Lookup cancelled.")
                            else -> return@withContext Pair(null, searchMessage(response))
                        }
                        client = null
                    }
                    if (cancelled) return@withContext Pair(null, "Lookup cancelled.")
                    Pair(Prepared(project, session.uri, exportName(session.fileName), omitted, omitted > 0 && project.presentKeys().size == omitted && project.hasBlaykieBibliography()), "")
                } catch (e: Exception) { Pair(null, exportError(e, stage)) }
            }
            client = null
            val ready = result.first
            if (ready == null) mutable.value = mutable.value.copy(phase = ExportPhase.IDLE, message = result.second, needsKey = result.second.startsWith("Some earlier citations"))
            else { prepared = ready; mutable.value = mutable.value.copy(phase = ExportPhase.CHOOSE, omitted = ready.omitted, message = "Choose where to save the document.") }
        }
    }
    fun cancelLookup() { cancelled = true; client?.cancel() }
    fun chooserLaunched(): Boolean {
        if (mutable.value.phase != ExportPhase.CHOOSE) return false
        mutable.value = mutable.value.copy(phase = ExportPhase.WAITING)
        return true
    }
    fun destination(uri: Uri?) {
        if (mutable.value.phase != ExportPhase.WAITING) return
        val work = prepared
        if (uri == null || work == null) {
            prepared = null; mutable.value = mutable.value.copy(phase = ExportPhase.IDLE, message = "Save cancelled."); return
        }
        mutable.value = mutable.value.copy(phase = ExportPhase.WRITING, message = "Exporting…")
        val line = mutable.value.line
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var stage = FailureStage.PRIVATE
                try {
                    val source = Uri.parse(work.sourceUri)
                    fun identity(value: Uri): Pair<String, String>? = try {
                        if (DocumentsContract.isDocumentUri(context, value)) value.authority?.let { it to DocumentsContract.getDocumentId(value) } else null
                    } catch (_: Exception) { null }
                    val sameSource = sameDocument(source.toString(), uri.toString(), identity(source), identity(uri))
                    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext Pair(null, "The destination is not writable.")
                    val file = File(File(context.cacheDir, CACHE_FOLDER), work.name)
                    writeVerifiedExport(work.project, file, line, work.omitted > 0, sameSource,
                        beforeCopy = { stage = FailureStage.DESTINATION }, destination = {
                            context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Destination unavailable")
                        })
                    val savedName = try { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
                    val saved = if (savedName.isNullOrBlank()) "Saved." else "Saved as $savedName."
                    Pair(file, "Exported (${file.length() / 1024} KB). $saved" + if (work.omitted > 0) " ${work.omitted} reference(s) not found." + if (work.oldBibliographyRetained) " The existing bibliography remains unchanged." else " They were omitted from the new list." else "")
                } catch (_: SourceDestinationConflict) { Pair(null, "Choose a different file. The original is never changed.") }
                catch (e: Exception) { Pair(null, exportError(e, stage)) }
            }
            prepared = null
            mutable.value = mutable.value.copy(phase = if (result.first == null) ExportPhase.IDLE else ExportPhase.DONE, message = result.second, shareFile = result.first)
        }
    }
    fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
    fun reset() { if (mutable.value.phase in setOf(ExportPhase.IDLE, ExportPhase.DONE)) { prepared = null; mutable.value = ExportState() } }
    override fun onCleared() { client?.cancel(); scope.cancel() }
    class Factory(private val context: Context, private val sessions: SessionStore, private val accounts: AccountStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ExportController(context.applicationContext, sessions, accounts) as T
    }
}
