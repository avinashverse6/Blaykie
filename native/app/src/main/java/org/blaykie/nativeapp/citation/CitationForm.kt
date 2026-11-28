package org.blaykie.nativeapp.citation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.AppTextButton
import org.blaykie.nativeapp.BlaykieCatLogo
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary

internal fun citationSubmitLabel(editing: Boolean) = if (editing) "Save" else "Insert citation"
internal fun citationSubmitEnabled(selectedCount: Int, loading: Boolean, inserting: Boolean, restricted: Boolean): Boolean =
    selectedCount > 0 && !loading && !inserting && !restricted
internal fun showSelectedReferences(count: Int): Boolean = count >= 2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CitationForm(state: SearchState, controller: CitationSearchController, paragraph: String,
                          onCancel: () -> Unit, onSubmit: () -> Unit, onAddKey: () -> Unit,
                          cslNote: String? = null, cslPreview: String? = null, restriction: String? = null, inserting: Boolean = false) {
    val editing = state.editNumber != null
    val canSelect = state.hasKey || editing
    val selected = remember(state.results, state.selected, state.existingItems, editing) { state.selectedItems() }
    var details by rememberSaveable(state.editNumber) {
        mutableStateOf(editing && (state.page.isNotBlank() || state.prefix.isNotBlank() || state.options.needsApa))
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = colors.surface,
        tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().imeNestedScroll(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (editing) "Edit citation" else "Add citation", Modifier.weight(1f),
                            style = typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Surface(Modifier.size(40.dp), shape = CircleShape, color = colors.surfaceVariant) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_close), "Close citation form", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                item { CitationContext(paragraph, state.position) }
                if (!canSelect) {
                    item { Text("Add a Zotero key before searching.") }
                    item { AppPrimaryButton(onClick = onAddKey) { Text("Add key") } }
                } else {
                    if (!editing) item { LibrarySelector(state, controller) }
                    if (!editing) item {
                        OutlinedTextField(value = state.query, onValueChange = controller::query,
                            placeholder = { Text("Search your Zotero library") }, singleLine = true, shape = CircleShape,
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                            leadingIcon = { IconButton(onClick = controller::search) { Icon(painterResource(R.drawable.ic_search), "Search Zotero") } },
                            trailingIcon = if (state.query.isNotEmpty()) {
                                { IconButton(onClick = { controller.query("") }) { Icon(painterResource(R.drawable.ic_close), "Clear search") } }
                            } else null)
                    }
                    if (!editing && state.query.isBlank() && selected.isEmpty() && !state.loading) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BlaykieCatLogo(72.dp)
                            Text("Search your Zotero library", style = typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                    }
                    if (state.loading) item { CircularProgressIndicator() }
                    if (state.message.isNotEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.message == "Zotero error: 404") "User ID not found. Check your Zotero User ID." else state.message,
                                Modifier.weight(1f), style = typography.bodyMedium)
                            if (!editing && state.message.startsWith("Network error")) TextButton(onClick = controller::search) { Text("Retry") }
                        }
                    }
                    if (editing) {
                        itemsIndexed(selected) { _, item -> ReferenceCard(item, true, onClick = null) }
                    } else {
                        itemsIndexed(state.results.take(state.shown)) { index, result ->
                            ReferenceCard(result.citationItem(), index in state.selected, onClick = { controller.toggle(index) },
                                groupName = (state.library as? ZoteroLibrary.Group)?.group?.name)
                        }
                        if (state.shown < state.results.size) item {
                            AppTextButton(onClick = controller::showMore) { Text("Show more (${state.results.size - state.shown})") }
                        }
                    }
                    if (!editing && showSelectedReferences(selected.size)) item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.selected.sorted().forEach { index ->
                                state.results.getOrNull(index)?.citationItem()?.let { item ->
                                    val name = item.authors.take(2).joinToString(" & ") { it.last }.ifBlank { item.title.ifBlank { "Untitled reference" } }
                                    InputChip(selected = true, onClick = { controller.toggle(index) }, modifier = Modifier.heightIn(min = 48.dp),
                                        label = { Text("$name ${CitationFormatting.displayYear(item)}") },
                                        trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Remove $name", Modifier.size(16.dp)) },
                                        shape = CircleShape, colors = InputChipDefaults.inputChipColors(selectedContainerColor = colors.primaryContainer,
                                            selectedLabelColor = colors.onPrimaryContainer, selectedTrailingIconColor = colors.onPrimaryContainer))
                                }
                            }
                        }
                    }
                    item { CitationStyleControl(state, controller) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
                                Text("Page, prefix, suffix", Modifier.weight(1f), style = typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(if (details) "▴" else "▾")
                            }
                            if (details) CitationOptionFields(state, controller)
                        }
                    }
                    if (selected.isNotEmpty()) item { CitationPreview(state, cslNote, cslPreview, restriction) }
                    else if (restriction != null) item { Text(restriction, style = typography.bodySmall) }
                }
            }
            CitationActions(editing, citationSubmitEnabled(selected.size, state.loading, inserting, restriction != null), onCancel, onSubmit)
        }
    }
}

@Composable
private fun CitationContext(paragraph: String, cursor: Int) {
    val position = cursor.coerceIn(0, paragraph.length)
    val start = (position - 60).coerceAtLeast(0)
    val end = (position + 60).coerceAtMost(paragraph.length)
    val colors = MaterialTheme.colorScheme
    val excerpt = buildAnnotatedString {
        if (start > 0) append("…")
        append(paragraph.substring(start, position))
        appendInlineContent("position", "Insertion point")
        append(paragraph.substring(position, end))
        if (end < paragraph.length) append("…")
    }
    val marker = InlineTextContent(Placeholder(.29f.em, .92f.em, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 1.dp).background(colors.primary, CircleShape))
    }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Goes here", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            Text(excerpt, inlineContent = mapOf("position" to marker), style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun CitationPreview(state: SearchState, cslNote: String?, cslPreview: String?, restriction: String?) {
    val colors = MaterialTheme.colorScheme
    val fallback = cslNote != null && state.savedOptions().needsApa
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            val preview = state.preview()
            if (preview.isNotEmpty()) Text(if (cslNote != null && !fallback) cslPreview ?: cslNote else preview,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (fallback) Text("Options apply to the built-in APA style only", style = MaterialTheme.typography.bodySmall)
            if (restriction != null) Text(restriction, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LibrarySelector(state: SearchState, controller: CitationSearchController) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.groups.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.library is ZoteroLibrary.Personal,
                onClick = { controller.selectLibrary(ZoteroLibrary.Personal) }, label = { Text("My Library") },
                modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer))
            state.groups.forEach { group ->
                FilterChip(selected = (state.library as? ZoteroLibrary.Group)?.group?.id == group.id,
                    onClick = { controller.selectLibrary(ZoteroLibrary.Group(group)) }, label = { Text(group.name) },
                    modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer))
            }
        }
        if (state.groupsError != null) groupsMessage(state.groupsLoaded, state.groups, state.groupsError)?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                TextButton(onClick = controller::retryGroups) { Text("Retry") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitationStyleControl(state: SearchState, controller: CitationSearchController) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val segmentColors = SegmentedButtonDefaults.colors(activeContainerColor = colors.primaryContainer, activeContentColor = colors.onPrimaryContainer)
        SegmentedButton(selected = state.style == "parenthetical", onClick = { controller.style("parenthetical") },
            shape = SegmentedButtonDefaults.itemShape(0, 2, RoundedCornerShape(12.dp)), colors = segmentColors) { Text("(Author, 2020)") }
        SegmentedButton(selected = state.style == "narrative", onClick = { controller.style("narrative") },
            shape = SegmentedButtonDefaults.itemShape(1, 2, RoundedCornerShape(12.dp)), colors = segmentColors) { Text("Author (2020)") }
    }
}

@Composable
private fun ReferenceCard(item: CitationItem, checked: Boolean, onClick: (() -> Unit)?, groupName: String? = null) {
    val colors = MaterialTheme.colorScheme
    Crossfade(targetState = checked, animationSpec = tween(150), label = "Reference selection") { selected ->
        val shape = RoundedCornerShape(20.dp)
        val content: @Composable ColumnScope.() -> Unit = {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(if (selected) colors.primary else colors.surface, CircleShape)
                    .border(2.dp, if (selected) colors.primary else colors.outline, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Text("✓", color = colors.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    ReferenceText(item)
                    if (!groupName.isNullOrBlank()) Text(groupName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        val cardColors = CardDefaults.outlinedCardColors(containerColor = if (selected) colors.primaryContainer else colors.surface)
        val stroke = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline)
        if (onClick != null) OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { this.selected = checked },
            shape = shape, colors = cardColors, border = stroke, content = content)
        else OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = shape, colors = cardColors, border = stroke, content = content)
    }
}

@Composable
private fun ReferenceText(item: CitationItem) {
    val authors = item.authors.joinToString(", ") { it.last }.ifBlank { "Unknown author" }
    val venue = item.publicationTitle.ifBlank { item.publisher.ifBlank { item.bookTitle } }
    Text(item.title.ifBlank { "[No title]" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
    Text("$authors · ${CitationFormatting.displayYear(item)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (venue.isNotBlank()) Text(venue, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CitationActions(editing: Boolean, enabled: Boolean, onCancel: () -> Unit, onSubmit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column {
            HorizontalDivider(color = colors.outline, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = onSubmit, enabled = enabled, shape = CircleShape, modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = colors.onSurface.copy(alpha = 0.12f)),
                    elevation = null) {
                    Text(citationSubmitLabel(editing), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
