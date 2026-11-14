package org.blaykie.nativeapp.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.docx.DocxProject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportScreen(project: DocxProject, state: ExportState, controller: ExportController, onExport: () -> Unit, onClose: () -> Unit, onAddKey: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit) {
    var shareMenu by rememberSaveable { mutableStateOf(false) }
    val idle = state.phase == ExportPhase.IDLE || state.phase == ExportPhase.DONE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Export a new .docx", style = MaterialTheme.typography.headlineSmall)
        Text("Citations added now: ${project.citations().size}")
        Text("Blaykie references in the file: ${project.presentKeys().size}")
        val (removed, added) = project.editSummary()
        Text("Your own edits: $removed character(s) removed, $added added. Citations never remove text.")
        if (project.existingReferenceHeadings().isNotEmpty() && !project.hasBlaykieBibliography()) Text("Note: the file already has a References heading. Blaykie adds its own list at the end.")
        if (project.hasTrackedChanges()) Text("Note: the file has tracked changes. Citations are inserted as normal text.")
        if (project.hasZoteroFields()) Text("Zotero plugin citations are left untouched.")
        if (state.omitted > 0) Text("References not found: ${state.omitted}")
        Text("References line spacing", style = MaterialTheme.typography.titleSmall)
        Column {
            listOf("match" to "Like my document", "1.5" to "1.5 lines", "double" to "Double (APA)").forEach { (value, label) ->
                Row { RadioButton(selected = state.line == value, enabled = idle, onClick = { controller.setLine(value) }); Text(label, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
        if (state.phase != ExportPhase.FALLBACK && !idle) CircularProgressIndicator()
        PlatformExportHook.Summary(state)
        if (state.message.isNotEmpty()) Text(if (state.message == "Zotero error: 404") "User ID not found. Check your Zotero User ID." else state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.needsKey) Button(onClick = onAddKey) { Text("Add Zotero key") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = idle, onClick = onExport) { Text("Export now") }
            OutlinedButton(enabled = state.shareFile != null && idle, onClick = { shareMenu = true }) { Text("Share / open") }
        }
        if (state.phase == ExportPhase.PREPARING) TextButton(onClick = controller::cancelLookup) { Text("Cancel lookup") }
        TextButton(enabled = idle, onClick = onClose) { Text("Close") }
    }
    PlatformExportHook.Fallback(state, controller::fallbackToApa, controller::cancelFallback)
    if (shareMenu) AlertDialog(onDismissRequest = { shareMenu = false }, title = { Text("Share / open") }, text = { Text("Choose an action for the exported document.") }, confirmButton = {
        TextButton(onClick = { shareMenu = false; onShare() }) { Text("Share") }
    }, dismissButton = { TextButton(onClick = { shareMenu = false; onOpen() }) { Text("Open") } })
}
