package org.blaykie.nativeapp.export

import android.content.Context
import java.io.File
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.citation.style.citationUsesCsl
import org.blaykie.nativeapp.citation.style.exportCslStyle
import org.blaykie.nativeapp.citation.style.whenCustomStylesEnabled

internal data class ExportCslStyle(val title: String, val processor: org.blaykie.nativeapp.citation.processor.CitationProcessor, val language: String = "en-US")

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

internal fun optionalExportSelection(enabled: Boolean, session: Session, style: () -> ExportCslStyle?): ProcessorSelection? =
    whenCustomStylesEnabled(enabled && citationUsesCsl(session)) {
        val selected = style()
        ProcessorSelection(selected?.processor, selected?.title.orEmpty(), selected?.language.orEmpty())
    }

internal fun beginExport(context: Context, controller: ExportController, session: Session, enabled: Boolean) {
    val selection = optionalExportSelection(enabled, session) { exportCslStyle(context, session) }
    controller.begin(session, selection, showOptionalSummary = enabled)
}

/** Explicit transitions keep a failed preparation from implicitly choosing a fallback. */
internal fun preparationFailed(state: ExportState): ExportState = state.copy(
    phase = ExportPhase.FALLBACK,
    message = "CSL export could not be prepared. No destination was written. Choose built-in APA or cancel.",
)
internal fun chooseBuiltIn(state: ExportState, omitted: Int): ExportState {
    require(state.phase == ExportPhase.FALLBACK)
    return state.copy(phase = ExportPhase.CHOOSE, omitted = omitted, styleTitle = "", languageSummary = org.blaykie.nativeapp.csl.styleLanguageSummary(null, "en-US"), legacyCount = 0, message = "Choose where to save the document.")
}
internal fun cancelPreparation(state: ExportState): ExportState {
    require(state.phase == ExportPhase.FALLBACK)
    return state.copy(phase = ExportPhase.IDLE, message = "Export cancelled.")
}
