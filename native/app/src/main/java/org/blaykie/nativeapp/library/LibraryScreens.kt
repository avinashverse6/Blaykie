package org.blaykie.nativeapp.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.citation.groupsMessage
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.blaykie.nativeapp.zotero.collectionTree
import org.blaykie.nativeapp.zotero.flattenTree

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryScreen(state: LibraryState, controller: LibraryController, onSignIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    if (state.selectedItem != null) {
        ItemDetailScreen(state, controller)
        return
    }
    var collectionsOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size, state.endReached, state.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= state.items.size - 5) controller.loadMore() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.large)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.library is ZoteroLibrary.Personal,
                onClick = { controller.selectLibrary(ZoteroLibrary.Personal) }, label = { Text("My Library") }, shape = CircleShape)
            state.groups.forEach { group ->
                FilterChip(selected = (state.library as? ZoteroLibrary.Group)?.group?.id == group.id,
                    onClick = { controller.selectLibrary(ZoteroLibrary.Group(group)) }, label = { Text(group.name) }, shape = CircleShape)
            }
        }
        groupsMessage(state.groupsLoaded, state.groups, state.groupsError)?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = typography.bodySmall, color = colors.onSurfaceVariant)
                if (state.groupsError != null) TextButton(onClick = controller::retryGroups) { Text("Retry") }
            }
        }
        if (!state.signedIn) {
            Spacer(Modifier.height(Space.large))
            Text("Sign in with Zotero or add an API key to browse your library.", style = typography.bodyLarge)
            Spacer(Modifier.height(Space.medium))
            AppPrimaryButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in or enter a key") }
            return@Column
        }
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { controller.loadCollections(); collectionsOpen = true }, shape = CircleShape) { Text("Collections") }
            OutlinedButton(onClick = { controller.loadTags(); tagsOpen = true }, shape = CircleShape) { Text("Tags") }
            Box {
                OutlinedButton(onClick = { sortOpen = true }, shape = CircleShape) {
                    Text("Sort: ${state.sort.label} ${if (state.descending) "↓" else "↑"}")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    BrowseSort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.label) }, onClick = {
                            sortOpen = false
                            controller.setSort(sort, if (sort == BrowseSort.Title || sort == BrowseSort.Creator) false else true)
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (state.descending) "Descending ↓" else "Ascending ↑") }, onClick = {
                        sortOpen = false; controller.setSort(state.sort, !state.descending)
                    })
                }
            }
        }
        Spacer(Modifier.height(Space.small))
        OutlinedTextField(value = state.query, onValueChange = controller::query,
            placeholder = { Text("Search this library") }, singleLine = true, shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface),
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.search() }),
            leadingIcon = { IconButton(onClick = controller::search) { Icon(painterResource(R.drawable.ic_search), "Search") } },
            trailingIcon = if (state.query.isNotEmpty()) {
                { IconButton(onClick = { controller.query(""); controller.search() }) { Icon(painterResource(R.drawable.ic_close), "Clear search") } }
            } else null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.everything, onCheckedChange = controller::setEverything)
            Spacer(Modifier.width(4.dp))
            Text("Search everything", style = typography.bodyMedium)
        }
        val activeCollection = state.collections.firstOrNull { it.key == state.collectionKey }
        if (activeCollection != null || state.tag != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeCollection != null) InputChip(selected = true, onClick = { controller.chooseCollection(null) },
                    label = { Text(activeCollection.name) }, shape = CircleShape,
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Show all items", Modifier.size(14.dp)) })
                if (state.tag != null) InputChip(selected = true, onClick = { controller.setTag(null) },
                    label = { Text(state.tag) }, shape = CircleShape,
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Remove tag filter", Modifier.size(14.dp)) })
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(state.error, style = typography.bodyLarge)
                Spacer(Modifier.height(Space.medium))
                AppPrimaryButton(onClick = controller::retry) { Text("Retry") }
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No items", style = typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                items(state.items, key = { it.key + it.data.hashCode() }) { item ->
                    OutlinedCard(onClick = { controller.selectItem(item) }, modifier = Modifier.fillMaxWidth(), shape = AppShapes.medium,
                        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                        border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column(Modifier.padding(12.dp)) { ItemCardText(item) }
                    }
                }
                if (state.loadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (state.error != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error, Modifier.weight(1f), style = typography.bodySmall)
                        TextButton(onClick = controller::loadMore) { Text("Retry") }
                    }
                }
            }
        }
    }
    if (collectionsOpen) CollectionsSheet(state, controller) { collectionsOpen = false }
    if (tagsOpen) TagsSheet(state, controller) { tagsOpen = false }
}

@Composable
private fun ItemCardText(item: ZoteroItem) {
    val citation = remember(item) { item.citationItem() }
    val authors = citation.authors.joinToString(", ") { it.last }.ifBlank { "Unknown author" }
    val venue = citation.publicationTitle.ifBlank { citation.publisher.ifBlank { citation.bookTitle } }
    Text(citation.title.ifBlank { "[No title]" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("$authors · ${CitationFormatting.displayYear(citation)}", style = MaterialTheme.typography.bodyMedium)
    if (venue.isNotBlank()) Text(venue, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionsSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large)) {
            Text("Collections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Space.medium))
            when {
                state.collectionsError != null -> Column {
                    Text(state.collectionsError, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.loadCollections(force = true) }) { Text("Retry") }
                }
                !state.collectionsLoaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item {
                        TextButton(onClick = { controller.chooseCollection(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text("All items", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (state.collections.isEmpty()) item {
                        Text("No collections", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(flattenTree(collectionTree(state.collections)), key = { it.first.key }) { (collection, depth) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width((depth * 16).dp))
                            TextButton(onClick = { controller.chooseCollection(collection.key); onDismiss() },
                                modifier = Modifier.weight(1f)) {
                                Text(collection.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (collection.numItems != null) Text("${collection.numItems}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    var tagQuery by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large)) {
            Text("Tags", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Space.medium))
            OutlinedTextField(value = tagQuery, onValueChange = { tagQuery = it; controller.loadTags(it) },
                placeholder = { Text("Search tags") }, singleLine = true, shape = CircleShape,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.small))
            when {
                state.tagsError != null -> Column {
                    Text(state.tagsError, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.loadTags(tagQuery) }) { Text("Retry") }
                }
                state.tagsLoading && state.tags.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.tags.isEmpty() -> Text("No tags", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.tags, key = { it.name }) { tag ->
                        TextButton(onClick = { controller.setTag(tag.name); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(tag.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (tag.numItems != null) Text("${tag.numItems}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!state.tagsEnd) item {
                        TextButton(onClick = { controller.loadMoreTags(tagQuery) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.tagsLoading) "Loading…" else "Load more tags")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailScreen(state: LibraryState, controller: LibraryController) {
    val item = state.selectedItem ?: return
    BackHandler { controller.selectItem(null) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val collectionNames = remember(state.collections) { state.collections.associate { it.key to it.name } }
    val fields = remember(item, collectionNames) { itemDetailFields(item, collectionNames) }
    val doi = remember(item) { doiText(item) }
    val link = remember(item) { externalLink(item) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Item details") }, navigationIcon = {
            IconButton(onClick = { controller.selectItem(null) }) {
                Icon(painterResource(R.drawable.ic_arrow_back), "Back to library")
            }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.large),
            contentPadding = PaddingValues(vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            items(fields, key = { it.label }) { field ->
                Column {
                    Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(field.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (doi != null) OutlinedButton(onClick = { clipboard.setText(AnnotatedString(doi)) }, shape = CircleShape) { Text("Copy DOI") }
                    if (link != null) OutlinedButton(onClick = { runCatching { uriHandler.openUri(link) } }, shape = CircleShape) {
                        Text("Open DOI or URL")
                    }
                }
            }
        }
    }
}
