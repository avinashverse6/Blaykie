package org.blaykie.nativeapp.citation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.AppTextButton
import org.blaykie.nativeapp.BlaykieCatLogo
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
    val haptic = LocalHapticFeedback.current
    val typography = MaterialTheme.typography
    val fontScale = LocalDensity.current.fontScale
    val actions: @Composable () -> Unit = {
        CitationActions(editing, canSelect, selected.isNotEmpty() && !state.loading && !inserting && restriction == null, onCancel, onSubmit)
    }
    // The editor route swaps its entire content. Keep that navigation and give the full-screen form sheet styling.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Box(Modifier.fillMaxSize().background(colors.surfaceVariant)) {
        AnimatedVisibility(visible = entered, enter = slideInVertically(tween(220)) { it / 5 }) {
            Surface(Modifier.fillMaxSize(), color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // When the IME leaves almost no room, scrolling must still reach all controls.
                    val sticky = maxHeight >= (120 * fontScale).dp
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().imeNestedScroll(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(36.dp, 4.dp).background(colors.outlineVariant, CircleShape))
                                }
                            }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (editing) "Edit citation" else "Insert citation", Modifier.weight(1f),
                                        style = typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = onCancel, colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surfaceVariant)) {
                                        Icon(painterResource(R.drawable.ic_close), "Close citation form")
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
                                        placeholder = { Text("Search Zotero") }, singleLine = true, shape = CircleShape,
                                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface),
                                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                                        leadingIcon = {
                                            IconButton(onClick = controller::search) { Icon(painterResource(R.drawable.ic_search), "Search Zotero") }
                                        }, trailingIcon = if (state.query.isNotEmpty()) {
                                            { IconButton(onClick = { controller.query("") }) { Icon(painterResource(R.drawable.ic_close), "Clear search") } }
                                        } else null)
                                }
                                if (!editing && state.query.isBlank() && selected.isEmpty() && !state.loading) item {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BlaykieCatLogo(72.dp)
                                        Text("Search your Zotero library", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                                    }
                                }
                                if (selected.isNotEmpty()) item {
                                    if (editing) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { selected.forEach { ReferenceText(it) } }
                                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        state.selected.sorted().forEach { index ->
                                            state.results.getOrNull(index)?.citationItem()?.let { item ->
                                                val name = item.authors.take(2).joinToString(" & ") { it.last }.ifBlank { item.title.ifBlank { "Untitled reference" } }
                                                InputChip(selected = true, onClick = { controller.toggle(index) },
                                                    label = { Text("$name ${CitationFormatting.displayYear(item)}") },
                                                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Remove $name", Modifier.size(14.dp)) },
                                                    shape = CircleShape, colors = InputChipDefaults.inputChipColors(selectedContainerColor = colors.primary,
                                                        selectedLabelColor = colors.onPrimary, selectedTrailingIconColor = colors.onPrimary))
                                            }
                                        }
                                    }
                                }
                                item { CitationStyleControl(state, controller) }
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
                                            Text("Page, prefix, suffix", Modifier.weight(1f), style = typography.titleSmall)
                                            Text(if (details) "▴" else "▾")
                                        }
                                        if (details) CitationOptionFields(state, controller)
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
                                        OutlinedCard(onClick = {
                                            if (!checked) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            controller.toggle(index)
                                        }, modifier = Modifier.fillMaxWidth(), shape = AppShapes.medium,
                                            colors = CardDefaults.outlinedCardColors(containerColor = if (checked) colors.primaryContainer else colors.surface),
                                            border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) colors.primary else colors.outlineVariant)) {
                                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(26.dp).background(if (checked) colors.primary else colors.surface, CircleShape)
                                                    .border(1.5.dp, if (checked) colors.primary else colors.onSurfaceVariant, CircleShape),
                                                    contentAlignment = Alignment.Center) {
                                                    if (checked) Text("✓", color = colors.onPrimary, style = typography.labelLarge)
                                                }
                                                Column(Modifier.padding(start = 8.dp)) {
                                                    ReferenceText(item.citationItem())
                                                    (state.library as? org.blaykie.nativeapp.zotero.ZoteroLibrary.Group)?.let { group ->
                                                        Text(group.group.name, style = typography.labelSmall, color = colors.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (state.shown < state.results.size) item {
                                        AppTextButton(onClick = controller::showMore) { Text("Show more (${state.results.size - state.shown})") }
                                    }
                                }
                                if (selected.isNotEmpty() || restriction != null) item { CitationPreview(state, cslNote, restriction) }
                            }
                            if (!sticky) item { actions() }
                        }
                        if (sticky) actions()
                    }
                }
            }
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
    Surface(shape = AppShapes.medium, color = colors.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Citation goes here", style = MaterialTheme.typography.titleSmall, color = colors.onPrimaryContainer)
            Text(excerpt, inlineContent = mapOf("position" to marker), style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun CitationPreview(state: SearchState, cslNote: String?, restriction: String?) {
    val colors = MaterialTheme.colorScheme
    val fallback = cslNote != null && state.savedOptions().needsApa
    val preview = state.preview()
    Surface(shape = AppShapes.medium, color = colors.surface) {
        Column(Modifier.fillMaxWidth().drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(colors.primary.copy(alpha = .55f), topLeft = Offset(stroke / 2, stroke / 2),
                size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            if (preview.isNotEmpty()) Text(if (cslNote != null && !fallback) cslNote else preview, style = MaterialTheme.typography.bodyLarge)
            if (fallback) Text("Options apply to the built-in APA style only", style = MaterialTheme.typography.bodySmall)
            if (restriction != null) Text(restriction, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibrarySelector(state: SearchState, controller: CitationSearchController) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.library is org.blaykie.nativeapp.zotero.ZoteroLibrary.Personal,
                onClick = { controller.selectLibrary(org.blaykie.nativeapp.zotero.ZoteroLibrary.Personal) },
                label = { Text("My Library") }, shape = CircleShape)
            state.groups.forEach { group ->
                FilterChip(selected = (state.library as? org.blaykie.nativeapp.zotero.ZoteroLibrary.Group)?.group?.id == group.id,
                    onClick = { controller.selectLibrary(org.blaykie.nativeapp.zotero.ZoteroLibrary.Group(group)) },
                    label = { Text(group.name) }, shape = CircleShape)
            }
        }
        groupsMessage(state.groupsLoaded, state.groups, state.groupsError)?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.groupsError != null) TextButton(onClick = controller::retryGroups) { Text("Retry") }
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
    Text(item.title.ifBlank { "[No title]" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("$authors · ${CitationFormatting.displayYear(item)}", style = MaterialTheme.typography.bodyMedium)
    if (venue.isNotBlank()) Text(venue, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CitationActions(editing: Boolean, showSubmit: Boolean, enabled: Boolean, onCancel: () -> Unit, onSubmit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppTextButton(onClick = onCancel) { Text("Cancel") }
                if (showSubmit) Button(onClick = onSubmit, enabled = enabled, shape = CircleShape,
                    modifier = Modifier.heightIn(min = 50.dp).widthIn(min = 112.dp).then(
                        if (enabled) Modifier.shadow(5.dp, CircleShape) else Modifier),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)) {
                    Text(if (editing) "Save" else "Insert", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
