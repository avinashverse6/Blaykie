package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import org.blaykie.nativeapp.docx.DocxProject

internal data class PreviewRun(val text: String, val italic: Boolean, val bold: Boolean = false,
    val underline: Boolean = false, val smallCaps: Boolean = false)
internal data class BibliographyPreview(val entries: List<List<PreviewRun>>, val hangingIndent: Boolean)

internal fun builtInBibliographyPreview(project: DocxProject): BibliographyPreview =
    BibliographyPreview(project.bibliographyPreview().map { entry -> entry.map { (text, italic) -> PreviewRun(text, italic) } }, true)

@Composable
internal fun BibliographyPreviewScreen(project: DocxProject, styleKey: Any, onClose: () -> Unit,
                                        load: suspend () -> BibliographyPreview) {
    var attempt by remember { mutableIntStateOf(0) }
    var preview by remember(project, styleKey, attempt) { mutableStateOf<BibliographyPreview?>(null) }
    var error by remember(project, styleKey, attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(project, styleKey, attempt) {
        try {
            preview = load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = if (project.missingKeys().isNotEmpty()) "Some earlier citations are missing reference details."
                else "Could not prepare the bibliography. Try again."
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to document")
            }
            Text("Bibliography preview", style = MaterialTheme.typography.titleLarge)
        }
        val result = preview
        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(error.orEmpty())
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            result == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            result.entries.isEmpty() -> Text("No citations yet.", Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(result.entries) { entry ->
                    val text = buildAnnotatedString {
                        entry.forEach { run ->
                            withStyle(SpanStyle(
                                fontStyle = if (run.italic) FontStyle.Italic else null,
                                fontWeight = if (run.bold) FontWeight.Bold else null,
                                textDecoration = if (run.underline) TextDecoration.Underline else null,
                                fontFeatureSettings = if (run.smallCaps) "smcp" else null,
                            )) { append(run.text) }
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge.copy(
                        textIndent = if (result.hangingIndent) TextIndent(restLine = 36.sp) else TextIndent.None,
                    ))
                }
            }
        }
    }
}
