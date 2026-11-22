package org.blaykie.nativeapp.export

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.BibliographyOptions
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroResult

internal const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
internal const val CACHE_FOLDER = "export-share"
internal fun exportName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0 && base.substring(0, dot).any { it != '.' }) base.substring(0, dot) else base
    val clean = stem.replace(Regex("[^A-Za-z0-9_\\- ]+"), "").trim().replace(' ', '_')
    return (clean.ifEmpty { "Document" }) + "_Blaykie.docx"
}
internal fun cleanExportCache(cache: File) {
    val folder = File(cache, CACHE_FOLDER)
    if (folder.exists()) check(folder.isDirectory && folder.canonicalFile == File(cache.canonicalFile, CACHE_FOLDER))
    folder.listFiles()?.forEach { if (it.isFile) check(it.delete()) }
    check(folder.isDirectory || folder.mkdirs())
}
internal enum class FailureStage { SOURCE, DESTINATION, PRIVATE }
internal fun exportError(error: Throwable, stage: FailureStage): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it.message?.contains("ENOSPC", true) == true || it.message?.contains("no space", true) == true }) return "Not enough space to export the document."
    if (stage == FailureStage.SOURCE && chain.any { it is SecurityException || it is FileNotFoundException }) return "The source file is missing or access was revoked."
    if (stage == FailureStage.DESTINATION && chain.any { it is SecurityException || it is IOException }) return "The destination is not writable. The file may be incomplete."
    return "Could not export the document."
}
internal fun sameDocument(source: String, target: String, sourceId: Pair<String, String>?, targetId: Pair<String, String>?): Boolean =
    source == target || (sourceId != null && targetId != null && sourceId == targetId)
internal fun recoverReferences(project: DocxProject, client: ZoteroClient, userId: Long, key: String,
                               groups: List<org.blaykie.nativeapp.zotero.ZoteroGroup> = emptyList()): ZoteroResult<Int> {
    val missing = project.missingKeys().sorted()
    if (missing.isEmpty()) return ZoteroResult.Success(0)
    // Item keys are only unique inside a library: personal library first, then each group.
    for (group in missing.chunked(200)) {
        when (val response = client.fetchItems(userId, key, group)) {
            is ZoteroResult.Success -> project.registerItems(response.value.map { it.citationItem() })
            is ZoteroResult.EmptyLibrary -> Unit
            is ZoteroResult.Cancelled -> return response
            is ZoteroResult.InvalidKey -> return response
            is ZoteroResult.RateLimited -> return response
            is ZoteroResult.NetworkFailure -> return response
            is ZoteroResult.MalformedJson -> return response
            is ZoteroResult.HttpError -> return response
        }
    }
    return recoverFromGroups(project, client, userId, key, groups)
}

private fun recoverFromGroups(project: DocxProject, client: ZoteroClient, userId: Long, key: String,
                              groups: List<org.blaykie.nativeapp.zotero.ZoteroGroup>): ZoteroResult<Int> {
    for (group in groups) {
        val remaining = project.missingKeys().sorted()
        if (remaining.isEmpty()) break
        for (chunk in remaining.chunked(200)) {
            // A group this key cannot read (403) is skipped quietly, never an error.
            when (val response = client.fetchItems(org.blaykie.nativeapp.zotero.ZoteroLibrary.Group(group), userId, key, chunk)) {
                is ZoteroResult.Success -> project.registerItems(response.value.map { it.citationItem() })
                is ZoteroResult.Cancelled -> return response
                is ZoteroResult.RateLimited -> return response
                else -> Unit
            }
        }
    }
    return ZoteroResult.Success(project.missingKeys().size)
}

/**
 * Recovery including group libraries: the personal library is tried first (exactly as before,
 * with no extra request when it finds everything); the group list is loaded quietly and only
 * when items are still missing.
 */
internal fun recoverReferencesWithGroups(project: DocxProject, client: ZoteroClient, userId: Long, key: String): ZoteroResult<Int> {
    if (project.missingKeys().isEmpty()) return ZoteroResult.Success(0)
    val personal = recoverReferences(project, client, userId, key)
    if (personal !is ZoteroResult.Success || project.missingKeys().isEmpty()) return personal
    val groups = (client.groups(userId, key) as? ZoteroResult.Success)?.value ?: emptyList()
    if (groups.isEmpty()) return personal
    return recoverFromGroups(project, client, userId, key, groups)
}

internal fun mayStartExport(phase: ExportPhase): Boolean = phase == ExportPhase.IDLE || phase == ExportPhase.DONE
internal fun shouldCancelOptionalExport(optional: Boolean, phase: ExportPhase): Boolean =
    optional && phase in setOf(ExportPhase.PREPARING, ExportPhase.FALLBACK, ExportPhase.CHOOSE, ExportPhase.WAITING)

internal class SourceDestinationConflict : IllegalArgumentException()
internal fun writeVerifiedExport(project: DocxProject, file: File, line: String, allowMissing: Boolean, sameSource: Boolean, beforeCopy: () -> Unit, destination: () -> OutputStream, options: BibliographyOptions = BibliographyOptions()) {
    if (sameSource) throw SourceDestinationConflict()
    project.export(file, line, allowMissing, options)
    DocxProject(file)
    beforeCopy()
    destination().use { target -> file.inputStream().use { it.copyTo(target) }; target.flush() }
}

internal fun failedCopyMessage(error: Throwable, name: String, deletePartial: () -> Boolean): String {
    val reason = exportError(error, FailureStage.DESTINATION).removeSuffix(" The file may be incomplete.")
    val removed = try { deletePartial() } catch (_: Exception) { false }
    return if (removed) "$reason The incomplete file was deleted."
    else "$reason A partial file named \"$name\" may remain."
}
