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

internal data class BibliographyViewState(
    val content: BibliographyPreviewContent?, val fetching: Int, val error: String?,
    val keyMissing: Boolean, val lookupAttempted: Boolean, val retry: () -> Unit,
)

@Composable
internal fun rememberBibliographyView(
    project: DocxProject, styleKey: Any, options: BibliographyOptions, revision: Int = 0, bibliographyOnly: Boolean = false,
    prepare: suspend ((Int) -> Unit) -> PreviewReferences, buildXml: suspend (DocxProject, Boolean) -> String,
): BibliographyViewState {
    var attempt by remember { mutableIntStateOf(0) }
    var fetching by remember(project, revision, options.enabled, attempt) { mutableIntStateOf(0) }
    var references by remember(project, revision, options.enabled, attempt) { mutableStateOf<PreviewReferences?>(null) }
    var prepareFailed by remember(project, revision, options.enabled, attempt) { mutableStateOf(false) }
    LaunchedEffect(project, revision, options.enabled, attempt) {
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
            preview = renderBibliographyPreview(ready, bibliographyOnly = bibliographyOnly, buildXml = buildXml)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            renderFailed = true
        }
    }
    return BibliographyViewState(preview, fetching,
        lookupFailure?.message ?: if (prepareFailed || renderFailed) "Could not prepare the bibliography. Try again." else null,
        lookupFailure?.keyMissing == true, ready?.lookupAttempted == true, retry = { attempt++ })
}

@Composable
internal fun BibliographyPreviewScreen(project: DocxProject, styleKey: Any, options: BibliographyOptions,
                                        onClose: () -> Unit,
                                        prepare: suspend ((Int) -> Unit) -> PreviewReferences,
                                        buildXml: suspend (DocxProject, Boolean) -> String) {
    val state = rememberBibliographyView(project, styleKey, options, prepare = prepare, buildXml = buildXml)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to document")
            }
            Text("Bibliography preview", style = MaterialTheme.typography.titleLarge)
        }
        val result = state.content
        if (result != null && state.lookupAttempted) {
            TextButton(onClick = state.retry, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry") }
        }
        when {
            state.error != null || result == null -> BibliographyStatus(state, Modifier.padding(16.dp))
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

internal fun LazyListScope.bibliography(paragraphs: List<EndPreviewParagraph>) {
    items(paragraphs.size) { index -> PreviewParagraph(paragraphs[index], centered = index == 0) }
}

@Composable
internal fun BibliographyStatus(state: BibliographyViewState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val error = state.error
        if (error != null) {
            Text(error)
            if (state.keyMissing) Text("Check your Zotero key in Settings > Account.")
            TextButton(onClick = state.retry) { Text("Retry") }
        } else {
            CircularProgressIndicator()
            if (state.fetching > 0) Text("Fetching reference details... (${state.fetching} items)")
        }
    }
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
