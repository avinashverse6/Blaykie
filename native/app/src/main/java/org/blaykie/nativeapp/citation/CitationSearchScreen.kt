package org.blaykie.nativeapp.citation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.docx.CitationFormatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CitationSearchScreen(state: SearchState, controller: CitationSearchController, onCancel: () -> Unit, onInsert: () -> Unit, onAddKey: () -> Unit) {
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Insert citation", style = MaterialTheme.typography.headlineSmall)
        if (!state.hasKey) {
            Text("Add a Zotero key before searching.")
            Button(onClick = onAddKey) { Text("Add key") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = state.query, onValueChange = controller::query, label = { Text("Search your Zotero library") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = controller::search) { Text("Search") }
            }
            if (state.loading) CircularProgressIndicator()
            if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.results) { index, item ->
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.style == "parenthetical", onClick = { controller.style("parenthetical") }, label = { Text("(Author, 2020)") })
                FilterChip(selected = state.style == "narrative", onClick = { controller.style("narrative") }, label = { Text("Author (2020)") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.page, onValueChange = controller::page, label = { Text("Page, e.g. 15") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = state.prefix, onValueChange = controller::prefix, label = { Text("Prefix: see") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            val preview = state.preview()
            Text(if (preview.isEmpty()) "Select one or more references." else "Will insert:  $preview", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (state.hasKey) Button(enabled = !state.loading, onClick = onInsert) { Text("Insert") }
        }
    }
}
