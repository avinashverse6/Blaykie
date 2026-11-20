package org.blaykie.nativeapp.citation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.AppTextButton
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CitationForm(state: SearchState, controller: CitationSearchController, paragraph: String,
                          onCancel: () -> Unit, onSubmit: () -> Unit, onAddKey: () -> Unit,
                          cslNote: String? = null, restriction: String? = null, inserting: Boolean = false) {
    BackHandler { onCancel() }
    val editing = state.editNumber != null
    val canSelect = state.hasKey || editing
    val selected = remember(state.results, state.selected, state.existingItems, editing) { state.selectedItems() }
    var details by rememberSaveable(state.editNumber) {
        mutableStateOf(editing && (state.page.isNotBlank() || state.prefix.isNotBlank() || state.savedOptions().needsApa))
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val fontScale = LocalDensity.current.fontScale
    val actions: @Composable () -> Unit = {
        CitationActions(editing, canSelect, selected.isNotEmpty() && !state.loading && !inserting && restriction == null, onCancel, onSubmit)
    }
    // The current navigation replaces the paragraph editor with this form, so use a full-screen surface.
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // At very short keyboard/landscape heights, leave the entire screen scrollable.
            val sticky = maxHeight >= (160 * fontScale).dp
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().imeNestedScroll(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(if (editing) "Edit citation" else "Insert citation", style = typography.headlineSmall)
                    }
                    item {
                        val position = state.position.coerceIn(0, paragraph.length)
                        val start = (position - 60).coerceAtLeast(0)
                        val end = (position + 60).coerceAtMost(paragraph.length)
                        Surface(shape = AppShapes.medium, color = colors.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Citation will be added at the marked position", style = typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                                Text(paragraph.substring(start, position) + "|" + paragraph.substring(position, end),
                                    style = typography.bodyMedium, color = colors.onSurface)
                            }
                        }
                    }
                    if (!canSelect) {
                        item { Text("Add a Zotero key before searching.") }
                        item { AppPrimaryButton(onClick = onAddKey) { Text("Add key") } }
                    } else {
                        if (!editing) item {
                            OutlinedTextField(value = state.query, onValueChange = controller::query,
                                label = { Text("Search Zotero") }, singleLine = true, shape = AppShapes.small,
                                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                                leadingIcon = {
                                    IconButton(onClick = controller::search) { Icon(painterResource(R.drawable.ic_search), "Search Zotero") }
                                }, trailingIcon = if (state.query.isNotEmpty()) {
                                    { IconButton(onClick = { controller.query("") }) { Icon(painterResource(R.drawable.ic_close), "Clear search") } }
                                } else null)
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Selected (${selected.size})", style = typography.titleSmall)
                                if (editing) selected.forEach { ReferenceText(it) }
                                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.selected.sorted().forEach { index ->
                                        state.results.getOrNull(index)?.citationItem()?.let { item ->
                                            val name = item.authors.firstOrNull()?.last ?: item.title.ifBlank { "Untitled reference" }
                                            InputChip(selected = true, onClick = { controller.toggle(index) },
                                                label = { Text(name) }, trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Remove $name", Modifier.size(16.dp)) },
                                                shape = AppShapes.small)
                                        }
                                    }
                                }
                            }
                        }
                        item { CitationStyleControl(state, controller) }
                        item {
                            OutlinedCard(shape = AppShapes.medium, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (details) "Details  −" else "Details  +", style = typography.titleSmall)
                                }
                                if (details) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                                    CitationOptionFields(state, controller)
                                }
                            }
                        }
                        item {
                            val preview = state.preview()
                            val apaFallback = cslNote != null && state.savedOptions().needsApa
                            Surface(shape = AppShapes.small, color = colors.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(when {
                                        preview.isEmpty() -> "Select one or more references."
                                        cslNote != null && !apaFallback -> cslNote
                                        else -> "Preview: $preview"
                                    }, style = typography.bodyMedium)
                                    if (apaFallback) Text("Options apply to the built-in APA style only", style = typography.bodySmall)
                                    if (restriction != null) Text(restriction, style = typography.bodySmall)
                                }
                            }
                        }
                        if (state.loading) item { CircularProgressIndicator() }
                        if (state.message.isNotEmpty()) item {
                            Text(if (state.message == "Zotero error: 404") "User ID not found. Check your Zotero User ID." else state.message,
                                style = typography.bodyMedium)
                        }
                        if (!editing) {
                            itemsIndexed(state.results.take(state.shown)) { index, item ->
                                val checked = index in state.selected
                                OutlinedCard(onClick = { controller.toggle(index) }, modifier = Modifier.fillMaxWidth(), shape = AppShapes.medium,
                                    colors = CardDefaults.outlinedCardColors(containerColor = if (checked) colors.primaryContainer else colors.surface),
                                    border = BorderStroke(1.dp, if (checked) colors.primary else colors.outlineVariant)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                        Column(Modifier.padding(start = 8.dp)) { ReferenceText(item.citationItem()) }
                                    }
                                }
                            }
                            if (state.shown < state.results.size) item {
                                AppTextButton(onClick = controller::showMore) { Text("Show more (${state.results.size - state.shown})") }
                            }
                        }
                    }
                    if (!sticky) item { actions() }
                }
                if (sticky) actions()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitationStyleControl(state: SearchState, controller: CitationSearchController) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = state.style == "parenthetical", onClick = { controller.style("parenthetical") },
            shape = SegmentedButtonDefaults.itemShape(0, 2, AppShapes.small)) { Text("(Author, 2020)") }
        SegmentedButton(selected = state.style == "narrative", onClick = { controller.style("narrative") },
            shape = SegmentedButtonDefaults.itemShape(1, 2, AppShapes.small)) { Text("Author (2020)") }
    }
}

@Composable
private fun ReferenceText(item: CitationItem) {
    val authors = item.authors.joinToString(", ") { it.last }.ifBlank { "Unknown author" }
    val venue = item.publicationTitle.ifBlank { item.publisher.ifBlank { item.bookTitle } }
    Text(item.title.ifBlank { "[No title]" }, style = MaterialTheme.typography.titleSmall)
    Text("$authors · ${CitationFormatting.displayYear(item)}", style = MaterialTheme.typography.bodyMedium)
    if (venue.isNotBlank()) Text(venue, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CitationActions(editing: Boolean, showSubmit: Boolean, enabled: Boolean, onCancel: () -> Unit, onSubmit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            AppTextButton(onClick = onCancel) { Text("Cancel") }
            if (showSubmit) AppPrimaryButton(onClick = onSubmit, enabled = enabled) { Text(if (editing) "Save" else "Insert") }
        }
    }
}
