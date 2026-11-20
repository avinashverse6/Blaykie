package org.blaykie.nativeapp.citation

import org.blaykie.nativeapp.citation.style.citationPreviewNote
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import org.blaykie.nativeapp.docx.CitationFormatting

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CitationSearchScreen(state: SearchState, controller: CitationSearchController, paragraph: String, onCancel: () -> Unit, onInsert: () -> Unit, onAddKey: () -> Unit, cslSelected: Boolean = false, restriction: String? = null, inserting: Boolean = false) {
    BackHandler { onCancel() }
    val editing = state.editNumber != null
    // One scrollable viewport keeps the query and the bottom fields reachable when
    // the IME or a larger text size leaves little vertical space.
    LazyColumn(Modifier.fillMaxSize().imeNestedScroll(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (editing) "Edit citation" else "Insert citation", style = MaterialTheme.typography.headlineSmall) }
        if (!state.hasKey && !editing) {
            item { Text("Add a Zotero key before searching.") }
            item { Button(onClick = onAddKey) { Text("Add key") } }
        } else {
            if (!editing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = state.query, onValueChange = controller::query, label = { Text("Search your Zotero library") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { controller.search() }), modifier = Modifier.weight(1f))
                        TextButton(onClick = controller::search) { Text("Search") }
                    }
                }
                if (state.loading) item { CircularProgressIndicator() }
                if (state.message.isNotEmpty()) item { Text(if (state.message == "Zotero error: 404") "User ID not found. Check your Zotero User ID." else state.message, style = MaterialTheme.typography.bodyMedium) }
                itemsIndexed(state.results.take(state.shown)) { index, item ->
                    val data = item.citationItem()
                    val authors = if (data.editorLead) emptyList() else data.authors
                    val name = when (authors.size) {
                        0 -> "Unknown author"
                        1 -> authors[0].last
                        2 -> "${authors[0].last} & ${authors[1].last}"
                        else -> "${authors[0].last} et al."
                    }
                    Card(onClick = { controller.toggle(index) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = index in state.selected, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("$name · ${CitationFormatting.displayYear(data)}", style = MaterialTheme.typography.titleSmall)
                                Text(data.title.ifEmpty { "[No title]" }.take(100), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (state.shown < state.results.size) item {
                    TextButton(onClick = controller::showMore) { Text("Show more (${state.results.size - state.shown})") }
                }
            } else item {
                state.existingItems.forEach { Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = 2) }
                if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.style == "parenthetical", onClick = { controller.style("parenthetical") }, label = { Text("(Author, 2020)") })
                    FilterChip(selected = state.style == "narrative", onClick = { controller.style("narrative") }, label = { Text("Author (2020)") })
                }
            }
            item { CitationOptionFields(state, controller) }
            item {
                val position = state.position.coerceIn(0, paragraph.length)
                val start = (position - 60).coerceAtLeast(0); val end = (position + 60).coerceAtMost(paragraph.length)
                // The list already provides 8 dp above this item.
                Text(paragraph.substring(start, position) + "|" + paragraph.substring(position, end),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                val preview = state.preview()
                Text(if (preview.isEmpty()) "Select one or more references." else if (cslSelected && !state.savedOptions().needsApa) citationPreviewNote() else "Preview: $preview", style = MaterialTheme.typography.bodyMedium)
                if (cslSelected && state.savedOptions().needsApa) Text("Options apply to the built-in APA style only", style = MaterialTheme.typography.bodySmall)
                if (restriction != null) Text(restriction, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (state.hasKey || editing) Button(enabled = !state.loading && !inserting && restriction == null, onClick = onInsert) { Text(if (editing) "Save" else "Insert") }
            }
        }
    }
}
