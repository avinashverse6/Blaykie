package org.blaykie.nativeapp.export

import androidx.compose.runtime.Composable
import java.io.File
import org.blaykie.nativeapp.citation.processor.CitationProcessor
import org.blaykie.nativeapp.docx.DocxProject

/** Optional processor choice; a missing processor represents an unavailable selection. */
internal data class ProcessorSelection(val processor: CitationProcessor?, val title: String = "", val language: String = "")
internal data class ExportDetails(val file: File, val title: String, val legacyCount: Int, val languageSummary: String)

/** Variant-specific preparation and presentation; the standard export path does not use this hook. */
internal interface ExportHook {
    fun initialLanguageSummary(hasSelection: Boolean): String
    suspend fun prepare(project: DocxProject, file: File, line: String, allowMissing: Boolean, selection: ProcessorSelection): ExportDetails?
    fun preparationFailed(state: ExportState): ExportState
    fun chooseBuiltIn(state: ExportState, omitted: Int): ExportState
    fun cancelPreparation(state: ExportState): ExportState
    fun completionSuffix(details: ExportDetails?): String
    @Composable fun Summary(state: ExportState)
    @Composable fun Fallback(state: ExportState, onConfirm: () -> Unit, onCancel: () -> Unit)
}
