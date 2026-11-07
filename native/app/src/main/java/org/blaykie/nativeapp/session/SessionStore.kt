package org.blaykie.nativeapp.session

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.CitationItem

@Serializable data class EditOperation(
    val block: Int, val text: String = "", val type: String = "edit", val position: Int = 0,
    val items: List<CitationItem> = emptyList(), val style: String = "parenthetical",
    val page: String = "", val prefix: String = "",
)
@Serializable data class Draft(val block: Int, val text: String)
@Serializable data class Session(val uri: String, val fileName: String, val sourceName: String, val edits: List<EditOperation> = emptyList(), val draft: Draft? = null, val version: Int = 1)
sealed class SessionRead {
    data class Ready(val session: Session, val project: DocxProject) : SessionRead()
    object Missing : SessionRead()
    object Invalid : SessionRead()
}

/** All paths and content are confined to the application's private files directory. */
class SessionStore(private val root: File) {
    private val sources = File(root, "session-sources")
    private val metadata = File(root, "session.json")
    private val json = Json { ignoreUnknownKeys = true }
    private fun source(name: String): File {
        require(Regex("[a-f0-9-]+\\.docx").matches(name))
        return File(sources, name)
    }
    @Synchronized fun read(): SessionRead {
        if (!metadata.exists()) return SessionRead.Missing
        return try {
            val session = json.decodeFromString<Session>(metadata.readText(Charsets.UTF_8))
            require(session.version in 1..2)
            val file = source(session.sourceName)
            if (!file.isFile) return SessionRead.Invalid
            val project = DocxProject(file)
            for (edit in session.edits) {
                when (edit.type) {
                    "edit" -> require(project.applyTextEdit(edit.block, edit.text))
                    "citation" -> {
                        require(session.version >= 2)
                        project.insertCitation(edit.block, edit.position, edit.position, edit.items, edit.style, edit.page, edit.prefix)
                    }
                    else -> error("Unknown operation")
                }
            }
            if (session.draft != null) require(session.draft.block in 0 until project.blockCount)
            SessionRead.Ready(session, project)
        } catch (_: Exception) { SessionRead.Invalid }
    }
    @Synchronized fun save(session: Session) {
        require(source(session.sourceName).isFile)
        root.mkdirs()
        val temp = File(root, "session.json.tmp")
        temp.outputStream().use { stream ->
            stream.write(json.encodeToString(session.copy(version = 2)).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temp.renameTo(metadata)) { "Could not save session" }
    }
    /** Copies first and commits metadata only after the new document parses. */
    @Synchronized fun open(uri: String, name: String, copy: (File) -> Unit): SessionRead.Ready {
        sources.mkdirs()
        val newName = UUID.randomUUID().toString() + ".docx"
        val target = source(newName)
        try {
            copy(target)
            val project = DocxProject(target)
            val session = Session(uri, name, newName, version = 2)
            save(session)
            sources.listFiles()?.filter { it.name != newName }?.forEach { it.delete() }
            return SessionRead.Ready(session, project)
        } catch (error: Exception) { target.delete(); throw error }
    }
    @Synchronized fun end() {
        metadata.delete(); File(root, "session.json.tmp").delete()
        sources.listFiles()?.forEach { it.delete() }
        sources.delete()
    }
}

enum class Screen { HOME, DOCUMENT, EDITOR, SETTINGS, LAST_CRASH }
fun backTarget(screen: Screen): Screen? = when (screen) {
    Screen.EDITOR -> Screen.DOCUMENT
    Screen.DOCUMENT, Screen.SETTINGS -> Screen.HOME
    Screen.LAST_CRASH -> Screen.SETTINGS
    Screen.HOME -> null
}
