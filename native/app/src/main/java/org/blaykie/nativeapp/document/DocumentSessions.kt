package org.blaykie.nativeapp.document

import java.io.InputStream
import org.blaykie.nativeapp.docx.Citation
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore

internal data class OpenDocument(val id: String, val store: SessionStore, val ready: SessionRead.Ready, val state: DocumentState)

/** Per-document session journals retain replay, undo, and export behavior without sharing edits across rows. */
internal fun DocumentRepository.importDocument(uri: String, name: String, input: InputStream): OpenDocument {
    val document = create(name, input)
    try {
        val store = document.sessionStore()
        return OpenDocument(document.id, store, store.attach(uri, name), document.state)
    } catch (error: Exception) {
        delete(document.id)
        throw error
    }
}

internal fun DocumentRepository.openDocument(id: String): OpenDocument? {
    val document = load(id) ?: return null
    val store = document.sessionStore()
    val ready = store.read() as? SessionRead.Ready ?: return null
    val session = ready.session.copy(fileName = document.state.displayName, bibliography = document.state.bibliography)
    if (session != ready.session) store.save(session)
    return OpenDocument(id, store, ready.copy(session = session), document.state)
}

private fun StoredDocument.sessionStore() = SessionStore(checkNotNull(workingDocx.parentFile), workingDocx)

internal fun DocumentRepository.recordSession(id: String, session: Session, style: String?, language: String?,
                                              position: DocumentPosition, languageExplicit: Boolean? = null, activeCitations: List<Citation>? = null, view: DocumentView? = null) {
    val current = load(id)?.state ?: return
    fun citation(block: Int, position: Int, keys: List<String>): InsertedCitation? {
        val ids = keys.filter { it.matches(zoteroItemId) }
        return if (ids.isEmpty()) null else InsertedCitation(block, position, ids)
    }
    val citations = activeCitations?.mapNotNull { citation(it.block, it.position, it.keys) }
        ?: session.edits.filter { it.type == "citation" }.mapNotNull { citation(it.block, it.position, it.items.map { item -> item.key }) }
    val updated = current.copy(citations = citations, bibliography = session.bibliography,
        citationStyle = style ?: current.citationStyle, citationLanguage = language ?: current.citationLanguage,
        citationLanguageExplicit = languageExplicit ?: current.citationLanguageExplicit, position = position, view = view ?: current.view)
    if (updated != current) saveState(id, updated)
}

private val zoteroItemId = Regex("[A-Z0-9]{8}")
