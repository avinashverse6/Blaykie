package org.blaykie.nativeapp.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.DocumentEndPreview
import org.blaykie.nativeapp.docx.SafeDocumentXml
import org.blaykie.nativeapp.docx.readDocumentEnd
import org.blaykie.nativeapp.docx.readBibliographyOnly
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.w3c.dom.Element
import org.w3c.dom.Node

internal sealed interface PreviewReferences {
    data class Ready(val project: DocxProject, val missing: Set<String>, val lookupAttempted: Boolean) : PreviewReferences {
        val allowMissing: Boolean get() = missing.isNotEmpty()
    }
    data class LookupFailed(val keyMissing: Boolean) : PreviewReferences {
        val message: String get() = "Could not reach Zotero"
    }
}

/** Replaying the session creates an independent in-memory project, without copying or writing the package. */
internal suspend fun preparePreviewReferences(
    store: SessionStore,
    sourceName: String,
    enabled: Boolean,
    readAccount: () -> AccountRead,
    onFetching: (Int) -> Unit = {},
    clientFactory: () -> ZoteroClient = ::ZoteroClient,
): PreviewReferences {
    val project = withContext(Dispatchers.IO) {
        val copy = checkNotNull(store.read() as? SessionRead.Ready)
        check(copy.session.sourceName == sourceName)
        copy.project
    }
    return preparePreviewReferences(project, enabled, readAccount, onFetching, clientFactory)
}

/** Recover details on an independent project; Mobile supplies a snapshot without replaying the document. */
internal suspend fun preparePreviewReferences(
    project: DocxProject,
    enabled: Boolean,
    readAccount: () -> AccountRead,
    onFetching: (Int) -> Unit = {},
    clientFactory: () -> ZoteroClient = ::ZoteroClient,
): PreviewReferences {
    val missing = withContext(Dispatchers.Default) { if (enabled) project.missingKeys() else emptySet() }
    if (missing.isEmpty()) return PreviewReferences.Ready(project, emptySet(), lookupAttempted = false)
    val account = (withContext(Dispatchers.IO) { readAccount() } as? AccountRead.Present)?.account
        ?: return PreviewReferences.LookupFailed(keyMissing = true)
    onFetching(missing.size)
    val client = clientFactory()
    val recovered = suspendCancellableCoroutine<ZoteroResult<Int>> { continuation ->
        continuation.invokeOnCancellation { client.cancel() }
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            continuation.resumeWith(runCatching { recoverReferences(project, client, account.verified.userId, account.key) })
        }
    }
    return if (recovered is ZoteroResult.Success) {
        PreviewReferences.Ready(project, withContext(Dispatchers.Default) { project.missingKeys() }, lookupAttempted = true)
    } else PreviewReferences.LookupFailed(keyMissing = false)
}

internal data class BibliographyPreviewContent(val end: DocumentEndPreview, val missingCitations: List<String>) {
    val missingNote: String? get() = missingCitations.takeIf { it.isNotEmpty() }?.let { "${it.size} citations have no reference details" }
}

internal suspend fun renderBibliographyPreview(
    prepared: PreviewReferences.Ready,
    bibliographyOnly: Boolean = false,
    buildXml: suspend (DocxProject, Boolean) -> String,
): BibliographyPreviewContent {
    val xml = buildXml(prepared.project, prepared.allowMissing)
    return withContext(Dispatchers.Default) {
        BibliographyPreviewContent(if (bibliographyOnly) readBibliographyOnly(xml) else readDocumentEnd(xml),
            missingCitationLabels(xml, prepared.missing))
    }
}

private val citationMarker = Regex("BLK_([A-Z0-9]+)_(\\d+)")

/** Use the visible text between the bookmark pair. Nested markers for one citation share a label. */
private fun missingCitationLabels(xml: String, missing: Set<String>): List<String> {
    if (missing.isEmpty()) return emptyList()
    data class Label(val keys: MutableSet<String> = linkedSetOf(), val text: StringBuilder = StringBuilder())
    return try {
        val doc = SafeDocumentXml.parseSnapshot(xml, 16 * 1024 * 1024)
        val labels = linkedMapOf<String, Label>()
        val active = linkedMapOf<String, Label>()
        fun visit(node: Node) {
            if (node is Element) when (node.tagName) {
                "w:bookmarkStart" -> {
                    val match = citationMarker.matchEntire(node.getAttribute("w:name"))
                    if (match != null && match.groupValues[1] in missing) {
                        val label = labels.getOrPut(match.groupValues[2]) { Label() }
                        label.keys.add(match.groupValues[1])
                        active[node.getAttribute("w:id")] = label
                    }
                }
                "w:bookmarkEnd" -> active.remove(node.getAttribute("w:id"))
                "w:t" -> if (active.isNotEmpty()) active.values.distinct().forEach {
                    if (it.text.length < 240) it.text.append(node.textContent.take(240 - it.text.length))
                }
            }
            var child = node.firstChild
            while (child != null) { visit(child); child = child.nextSibling }
        }
        visit(doc.documentElement)
        val seen = labels.values.flatMap { it.keys }.toSet()
        labels.values.map { it.text.toString().trim().ifEmpty { it.keys.joinToString(", ") } } + (missing - seen)
    } catch (_: Exception) {
        missing.sorted() // A label is optional; report the IDs without blocking the partial bibliography.
    }
}
