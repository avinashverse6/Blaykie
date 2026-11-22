package org.blaykie.nativeapp.document

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.blaykie.nativeapp.docx.BibliographyOptions

private const val DOCUMENT_SCHEMA_VERSION = 1

@Serializable
internal data class InsertedCitation(
    val paragraphIndex: Int,
    val characterOffset: Int,
    val zoteroItemIds: List<String>,
)

@Serializable
internal data class DocumentPosition(val paragraphIndex: Int = 0, val scrollOffset: Int = 0)

@Serializable
internal enum class DocumentView { Mobile, Print }

/** Only document metadata: no account details, source URI, or arbitrary metadata map. */
@Serializable
internal data class DocumentState(
    val displayName: String,
    val createdAtMillis: Long,
    val lastEditedAtMillis: Long,
    val citations: List<InsertedCitation> = emptyList(),
    val bibliography: BibliographyOptions = BibliographyOptions(),
    val citationStyle: String = "apa",
    val citationLanguage: String = "en-US",
    val position: DocumentPosition = DocumentPosition(),
    val view: DocumentView = DocumentView.Mobile,
    val lightPrintPage: Boolean = false,
    /** Last library chosen in Insert citation: 0 = My Library, otherwise a Zotero group id. */
    val citationLibraryGroupId: Long = 0,
    val schemaVersion: Int = DOCUMENT_SCHEMA_VERSION,
    val citationLanguageExplicit: Boolean = !citationLanguage.equals("en-US", ignoreCase = true),
)

internal data class StoredDocument(val id: String, val workingDocx: File, val state: DocumentState)

/** Private document storage, independent of the active single-document session. */
internal class DocumentRepository(filesDir: File, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(context.filesDir)

    private val root = File(filesDir, "documents")
    private val json = Json { encodeDefaults = true }

    /** Copies the picked file; the caller owns and closes [source]. */
    @Synchronized fun create(displayName: String, source: InputStream): StoredDocument {
        val timestamp = now()
        val state = DocumentState(displayName, timestamp, timestamp)
        require(valid(state)) { "Invalid document metadata" }
        if (!root.mkdirs() && !root.isDirectory) throw IOException("Could not create document storage")
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".$id.import")
        if (!staging.mkdir()) throw IOException("Could not stage document import")
        try {
            val docx = File(staging, DOCX)
            atomicWrite(docx) { source.copyTo(it) }
            ZipFile(docx).use {
                val part = it.getEntry("word/document.xml")
                if (part == null || part.isDirectory) throw IOException("Not a DOCX document")
            }
            writeState(staging, state)
            val folder = File(root, id)
            if (folder.exists() || !staging.renameTo(folder)) throw IOException("Could not publish document")
            return StoredDocument(id, File(folder, DOCX), state)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized fun list(): List<StoredDocument> = root.listFiles().orEmpty()
        .mapNotNull { load(it.name) }
        .sortedWith(compareByDescending<StoredDocument> { it.state.lastEditedAtMillis }.thenBy { it.id })

    /** Missing, damaged, or unsupported records are not resumable. They can still be deleted by ID. */
    @Synchronized fun load(id: String): StoredDocument? {
        val folder = folder(id) ?: return null
        val docx = File(folder, DOCX)
        if (!docx.isFile) return null
        return try {
            val state = json.decodeFromString(DocumentState.serializer(), File(folder, STATE).readText())
            if (valid(state)) StoredDocument(id, docx, state) else null
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    /** Returns the persisted snapshot, or null when the record cannot be loaded. */
    @Synchronized fun saveState(id: String, state: DocumentState): DocumentState? {
        val original = load(id)?.state ?: return null
        val updated = state.copy(createdAtMillis = original.createdAtMillis,
            lastEditedAtMillis = maxOf(now(), original.lastEditedAtMillis))
        require(valid(updated)) { "Invalid document metadata" }
        writeState(File(root, id), updated)
        return updated
    }

    @Synchronized fun delete(id: String): Boolean {
        val folder = folder(id)?.takeIf { it.isDirectory } ?: return false
        val trash = File(root, ".$id.${UUID.randomUUID()}.delete")
        return folder.renameTo(trash) && trash.deleteRecursively()
    }

    private fun folder(id: String): File? = if (documentId.matches(id)) File(root, id) else null

    private fun writeState(folder: File, state: DocumentState) {
        val bytes = json.encodeToString(DocumentState.serializer(), state).toByteArray(Charsets.UTF_8)
        atomicWrite(File(folder, STATE)) { it.write(bytes) }
    }

    private fun atomicWrite(destination: File, write: (FileOutputStream) -> Unit) {
        val temp = File.createTempFile(".${destination.name}.", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                write(output)
                output.fd.sync()
            }
            if (!temp.renameTo(destination)) throw IOException("Could not replace document data")
        } finally {
            temp.delete()
        }
    }

    private fun valid(state: DocumentState): Boolean = state.schemaVersion == DOCUMENT_SCHEMA_VERSION &&
        state.displayName.isNotBlank() && state.createdAtMillis >= 0 && state.lastEditedAtMillis >= state.createdAtMillis &&
        state.position.paragraphIndex >= 0 && state.position.scrollOffset >= 0 &&
        state.citationStyle.isNotBlank() && state.citationLanguage.isNotBlank() && state.citationLibraryGroupId >= 0 &&
        state.citations.all { it.paragraphIndex >= 0 && it.characterOffset >= 0 &&
            it.zoteroItemIds.isNotEmpty() && it.zoteroItemIds.all(zoteroItemId::matches) }

    private companion object {
        const val DOCX = "working.docx"
        const val STATE = "state.json"
        val documentId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val zoteroItemId = Regex("[A-Z0-9]{8}")
    }
}
