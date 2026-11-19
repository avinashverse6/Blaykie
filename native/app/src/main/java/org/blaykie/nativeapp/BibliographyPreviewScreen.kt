package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.blaykie.nativeapp.docx.DocxProject

import org.blaykie.nativeapp.docx.BibliographyOptions
import org.blaykie.nativeapp.docx.ReferenceList

@Composable
internal fun BibliographyPreviewScreen(project: DocxProject, styleKey: Any, options: BibliographyOptions,
                                        onOptionsChange: (BibliographyOptions) -> Boolean, onClose: () -> Unit,
                                        load: suspend () -> ReferenceList) {
    var saveFailed by remember(project) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var preview by remember(project, styleKey, options.enabled, attempt) { mutableStateOf<ReferenceList?>(null) }
    var error by remember(project, styleKey, options.enabled, attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(project, styleKey, options.enabled, attempt) {
        if (!options.enabled) return@LaunchedEffect
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
        val section = result?.section(options)
        when {
            !options.enabled -> Text("No bibliography will be added.", Modifier.padding(16.dp))
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(error.orEmpty())
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            result == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            section == null -> Text("No citations yet.", Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(section.heading, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                items(section.references.entries) { entry ->
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
                        textIndent = if (section.references.hangingIndent) TextIndent(restLine = 36.sp) else TextIndent.None,
                    ))
                }
            }
        }
    }
}
