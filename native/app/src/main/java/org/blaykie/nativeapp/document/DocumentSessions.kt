package org.blaykie.nativeapp.document

import java.io.InputStream
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore

internal data class OpenDocument(val id: String, val store: SessionStore, val ready: SessionRead.Ready)

/** Per-document session journals retain replay, undo, and export behavior without sharing edits across rows. */
internal fun DocumentRepository.importDocument(uri: String, name: String, input: InputStream): OpenDocument {
    val document = create(name, input)
    try {
        val store = document.sessionStore()
        return OpenDocument(document.id, store, store.attach(uri, name))
    } catch (error: Exception) {
        delete(document.id)
        throw error
    }
}

internal fun DocumentRepository.openDocument(id: String): OpenDocument? {
    val document = load(id) ?: return null
    val store = document.sessionStore()
    val ready = store.read() as? SessionRead.Ready ?: return null
    return OpenDocument(id, store, ready.copy(session = ready.session.copy(fileName = document.state.displayName)))
}

private fun StoredDocument.sessionStore() = SessionStore(checkNotNull(workingDocx.parentFile), workingDocx)

internal fun DocumentRepository.recordSession(id: String, session: Session, style: String, language: String) {
    val current = load(id)?.state ?: return
    val citations = session.edits.filter { it.type == "citation" }.mapNotNull { edit ->
        val ids = edit.items.map { it.key }.filter { it.matches(Regex("[A-Z0-9]{8}")) }
        if (ids.isEmpty()) null else InsertedCitation(edit.block, edit.position, ids)
    }
    saveState(id, current.copy(citations = citations, bibliography = session.bibliography,
        citationStyle = style, citationLanguage = language,
        position = session.draft?.let { DocumentPosition(it.block) } ?: current.position))
}
