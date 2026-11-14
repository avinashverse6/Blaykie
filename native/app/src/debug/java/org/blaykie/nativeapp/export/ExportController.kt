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

internal enum class ExportPhase { IDLE, PREPARING, FALLBACK, CHOOSE, WAITING, WRITING, DONE }
internal data class ExportState(
    val phase: ExportPhase = ExportPhase.IDLE, val line: String = "match", val name: String = "",
    val message: String = "", val needsKey: Boolean = false, val omitted: Int = 0,
    val shareFile: File? = null, val styleTitle: String = "", val legacyCount: Int = 0, val languageSummary: String = "",
) { override fun toString() = "ExportState(redacted)" }
private data class Prepared(val project: DocxProject, val session: Session, val sourceUri: String, val name: String, val omitted: Int, val oldBibliographyRetained: Boolean, val verifiedCslFile: File? = null, val styleTitle: String = "", val legacyCount: Int = 0)
internal class ExportController(private val context: Context, private val sessions: SessionStore, private val accounts: AccountStore) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = mutable
    @Volatile private var client: ZoteroClient? = null
    @Volatile private var cancelled = false
    private var prepared: Prepared? = null
    fun setLine(line: String) { if (mutable.value.phase in setOf(ExportPhase.IDLE, ExportPhase.DONE)) mutable.value = mutable.value.copy(line = line) }
    fun begin(session: Session, cslSelected: Boolean = false, csl: ExportCslStyle? = null) {
        if (!mayStartExport(mutable.value.phase)) return
        cancelled = false; prepared = null
        val line = mutable.value.line
        mutable.value = ExportState(phase = ExportPhase.PREPARING, line = line, name = exportName(session.fileName), languageSummary = if (cslSelected) "" else org.blaykie.nativeapp.csl.styleLanguageSummary(null, "en-US"), message = "Preparing export…")
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
                    Pair(Prepared(project, read.session, session.uri, exportName(session.fileName), omitted, omitted > 0 && project.presentKeys().size == omitted && project.hasBlaykieBibliography()), "")
                } catch (e: Exception) { Pair(null, exportError(e, stage)) }
            }
            client = null
            val ready = result.first
            if (ready == null) mutable.value = mutable.value.copy(phase = ExportPhase.IDLE, message = result.second, needsKey = result.second.startsWith("Some earlier citations"))
            else {
                prepared = ready
                if (!cslSelected) {
                    mutable.value = mutable.value.copy(phase = ExportPhase.CHOOSE, omitted = ready.omitted, message = "Choose where to save the document.")
                } else {
                    try {
                        val file = File(File(context.cacheDir, CACHE_FOLDER), ready.name)
                        val legacyCount = prepareCslExport(ready.project, file, line, ready.omitted > 0, csl)
                        check(!cancelled) { "Export cancelled." }
                        val title = requireNotNull(csl).title
                        prepared = ready.copy(verifiedCslFile = file, styleTitle = title, legacyCount = legacyCount)
                        mutable.value = mutable.value.copy(phase = ExportPhase.CHOOSE, omitted = ready.omitted, styleTitle = title, languageSummary = org.blaykie.nativeapp.csl.styleLanguageSummary(session.debugCitationStyleId, csl.language), legacyCount = legacyCount, message = "Choose where to save the document.")
                    } catch (e: Exception) {
                        mutable.value = preparationFailed(mutable.value)
                    }
                }
            }
        }
    }
    fun fallbackToApa() {
        if (mutable.value.phase != ExportPhase.FALLBACK || prepared == null) return
        mutable.value = chooseBuiltIn(mutable.value, prepared!!.omitted)
    }
    fun cancelFallback() {
        if (mutable.value.phase != ExportPhase.FALLBACK) return
        prepared = null
        mutable.value = cancelPreparation(mutable.value)
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
                var outputOpened = false
                val savedName = try { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
                val destinationName = savedName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: work.name
                try {
                    val source = Uri.parse(work.sourceUri)
                    fun identity(value: Uri): Pair<String, String>? = try {
                        if (DocumentsContract.isDocumentUri(context, value)) value.authority?.let { it to DocumentsContract.getDocumentId(value) } else null
                    } catch (_: Exception) { null }
                    val sameSource = sameDocument(source.toString(), uri.toString(), identity(source), identity(uri))
                    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext Pair(null, "The destination is not writable.")
                    val file = File(File(context.cacheDir, CACHE_FOLDER), work.name)
                    if (work.verifiedCslFile != null) {
                        if (sameSource) throw SourceDestinationConflict()
                        DocxProject(work.verifiedCslFile)
                        stage = FailureStage.DESTINATION
                        (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Destination unavailable")).also { outputOpened = true }.use { target ->
                            work.verifiedCslFile.inputStream().use { it.copyTo(target) }; target.flush()
                        }
                    } else writeVerifiedExport(work.project, file, line, work.omitted > 0, sameSource,
                        beforeCopy = { stage = FailureStage.DESTINATION }, destination = {
                            (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Destination unavailable")).also { outputOpened = true }
                        })
                    val tracked = sessions.recordExport(work.session)
                    val saved = if (savedName.isNullOrBlank()) "Saved." else "Saved as $savedName."
                    val warning = if (tracked) "" else " Export tracking could not be saved; edits may still be treated as unexported."
                    Pair(file, "Exported (${file.length() / 1024} KB). $saved" + (if (work.styleTitle.isNotEmpty()) " Style: ${work.styleTitle}. ${work.legacyCount} earlier citation(s) left unchanged." else "") + (if (work.omitted > 0) " ${work.omitted} reference(s) not found." + if (work.oldBibliographyRetained) " The existing bibliography remains unchanged." else " They were omitted from the new list." else "") + warning)
                } catch (_: SourceDestinationConflict) { Pair(null, "Choose a different file. The original is never changed.") }
                catch (e: Exception) {
                    val message = if (stage == FailureStage.DESTINATION && outputOpened) failedCopyMessage(e, destinationName) {
                        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                        else context.contentResolver.delete(uri, null, null) > 0
                    } else exportError(e, stage)
                    Pair(null, message)
                }
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
