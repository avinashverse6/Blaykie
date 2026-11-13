package org.blaykie.nativeapp.export

import android.content.Context
import java.io.File
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.citation.style.citationUsesCsl
import org.blaykie.nativeapp.citation.style.exportCslStyle

internal data class ExportCslStyle(val title: String, val processor: org.blaykie.nativeapp.citation.processor.CitationProcessor)

/** Prepare and reopen privately; caller must not offer a destination unless this succeeds. */
internal suspend fun prepareCslExport(project: DocxProject, file: File, line: String, allowMissing: Boolean, style: ExportCslStyle?): Int {
    try {
        val selected = requireNotNull(style) { "Citation style is not ready" }
        val input = project.citationExportInput(allowMissing)
        val result = if (input.items.isEmpty()) org.blaykie.nativeapp.citation.processor.BibliographyResult(emptyList(), org.blaykie.nativeapp.citation.processor.BibliographyLayout(), emptyList())
            else selected.processor.bibliography(input.items, input.clusters)
        project.exportWithProcessor(file, line, allowMissing, result)
        DocxProject(file)
        return input.legacyCount
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

internal fun beginExport(context: Context, controller: ExportController, session: Session) =
    controller.begin(session, citationUsesCsl(session), exportCslStyle(context, session))
