package org.blaykie.nativeapp.export

import androidx.compose.runtime.Composable
import java.io.File
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.BibliographyOptions

/** No optional processor or extra export UI is available in release. */
internal object PlatformExportHook : ExportHook {
    override fun initialLanguageSummary(hasSelection: Boolean) = ""
    override suspend fun prepare(project: DocxProject, file: File, line: String, allowMissing: Boolean, selection: ProcessorSelection, options: BibliographyOptions): ExportDetails? = null
    override fun preparationFailed(state: ExportState) = state
    override fun chooseBuiltIn(state: ExportState, omitted: Int) = state
    override fun cancelPreparation(state: ExportState) = state
    override fun completionSuffix(details: ExportDetails?) = ""
    @Composable override fun Summary(state: ExportState) { }
    @Composable override fun Fallback(state: ExportState, onConfirm: () -> Unit, onCancel: () -> Unit) { }
}
