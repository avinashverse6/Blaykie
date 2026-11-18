package org.blaykie.nativeapp.export

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import java.io.File
import org.blaykie.nativeapp.csl.styleLanguageSummary
import org.blaykie.nativeapp.docx.DocxProject

internal object PlatformExportHook : ExportHook {
    override fun initialLanguageSummary(hasSelection: Boolean) = if (hasSelection) "" else styleLanguageSummary(null, "en-US")
    override suspend fun prepare(project: DocxProject, file: File, line: String, allowMissing: Boolean, selection: ProcessorSelection): ExportDetails {
        val style = selection.processor?.let { ExportCslStyle(selection.title, it, selection.language) }
        val count = prepareCslExport(project, file, line, allowMissing, style)
        return ExportDetails(file, selection.title, count, styleLanguageSummary("selected", selection.language))
    }
    override fun preparationFailed(state: ExportState) = org.blaykie.nativeapp.export.preparationFailed(state)
    override fun chooseBuiltIn(state: ExportState, omitted: Int) = org.blaykie.nativeapp.export.chooseBuiltIn(state, omitted)
    override fun cancelPreparation(state: ExportState) = org.blaykie.nativeapp.export.cancelPreparation(state)
    override fun completionSuffix(details: ExportDetails?) = if (details != null && details.title.isNotEmpty())
        " Style: ${details.title}. ${details.legacyCount} earlier citation(s) left unchanged." else ""
    @Composable override fun Summary(state: ExportState) {
        if (state.styleTitle.isNotEmpty()) Text("Style: ${state.styleTitle}. Earlier citations left unchanged: ${state.legacyCount}")
        if (state.languageSummary.isNotEmpty()) Text(state.languageSummary)
    }
    @Composable override fun Fallback(state: ExportState, onConfirm: () -> Unit, onCancel: () -> Unit) {
        if (state.phase == ExportPhase.FALLBACK) AlertDialog(onDismissRequest = onCancel,
            title = { Text("Could not export with the selected citation style") }, text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Export with built-in APA") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
    }
}
