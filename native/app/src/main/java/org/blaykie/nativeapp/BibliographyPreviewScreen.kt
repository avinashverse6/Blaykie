package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.blaykie.nativeapp.docx.BibliographyOptions
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.EndPreviewParagraph
import org.blaykie.nativeapp.export.BibliographyPreviewContent
import org.blaykie.nativeapp.export.PreviewReferences
import org.blaykie.nativeapp.export.renderBibliographyPreview

@Composable
internal fun BibliographyPreviewScreen(project: DocxProject, styleKey: Any, options: BibliographyOptions,
                                        onOptionsChange: (BibliographyOptions) -> Boolean, onClose: () -> Unit,
                                        prepare: suspend ((Int) -> Unit) -> PreviewReferences,
                                        buildXml: suspend (DocxProject, Boolean) -> String) {
    var saveFailed by remember(project) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var fetching by remember(project, options.enabled, attempt) { mutableIntStateOf(0) }
    var references by remember(project, options.enabled, attempt) { mutableStateOf<PreviewReferences?>(null) }
    var prepareFailed by remember(project, options.enabled, attempt) { mutableStateOf(false) }
    LaunchedEffect(project, options.enabled, attempt) {
        try {
            references = prepare { fetching = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepareFailed = true
        } finally { fetching = 0 }
    }
    val ready = references as? PreviewReferences.Ready
    val lookupFailure = references as? PreviewReferences.LookupFailed
    var preview by remember(ready, styleKey, options) { mutableStateOf<BibliographyPreviewContent?>(null) }
    var renderFailed by remember(ready, styleKey, options) { mutableStateOf(false) }
    LaunchedEffect(ready, styleKey, options) {
        if (ready != null) try {
            preview = renderBibliographyPreview(ready, buildXml)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            renderFailed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to document")
            }
            Text("Bibliography preview", style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add to end of document", Modifier.weight(1f))
            Switch(checked = options.enabled, onCheckedChange = {
                saveFailed = !onOptionsChange(options.copy(enabled = it))
            }, modifier = Modifier.semantics { contentDescription = "Add to end of document" })
        }
        OutlinedTextField(
            value = options.heading,
            onValueChange = { saveFailed = !onOptionsChange(options.copy(heading = it)) },
            label = { Text("Heading") }, singleLine = true, enabled = options.enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (saveFailed) Text("Could not save bibliography options.", Modifier.padding(16.dp))
        val result = preview
        if (result != null && ready?.lookupAttempted == true) {
            TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry") }
        }
        when {
            lookupFailure != null || prepareFailed || renderFailed -> Column(Modifier.padding(16.dp)) {
                Text(lookupFailure?.message ?: "Could not prepare the bibliography. Try again.")
                if (lookupFailure?.keyMissing == true) Text("Check your Zotero key in Settings > Account.")
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            result == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                if (fetching > 0) Text("Fetching reference details... ($fetching items)")
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Preview (end of document)", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                }
                if (result.end.paragraphs.isEmpty()) item { Text("No document paragraphs.") }
                items(result.end.paragraphs) { PreviewParagraph(it) }
                when {
                    !options.enabled -> item { Text("Bibliography will not be added") }
                    result.end.bibliography.isEmpty() && result.missingCitations.isEmpty() -> item { Text("No citations yet.") }
                    else -> bibliography(result.end.bibliography)
                }
                result.missingNote?.let { note -> item { Text(note, style = MaterialTheme.typography.titleSmall) } }
                items(result.missingCitations) { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun LazyListScope.bibliography(paragraphs: List<EndPreviewParagraph>) {
    items(paragraphs.size) { index -> PreviewParagraph(paragraphs[index], centered = index == 0) }
}

@Composable
private fun PreviewParagraph(paragraph: EndPreviewParagraph, centered: Boolean = false) {
    val text = buildAnnotatedString {
        paragraph.runs.forEach { run ->
            withStyle(SpanStyle(
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontWeight = if (run.bold) FontWeight.Bold else null,
                textDecoration = if (run.underline) TextDecoration.Underline else null,
                fontFeatureSettings = if (run.smallCaps) "smcp" else null,
            )) { append(run.text) }
        }
    }
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        textIndent = TextIndent(restLine = (paragraph.hangingTwips / 20f).sp),
    ))
}
