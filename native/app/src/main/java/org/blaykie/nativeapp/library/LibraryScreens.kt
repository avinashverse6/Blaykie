package org.blaykie.nativeapp.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import org.blaykie.nativeapp.zotero.BrowseNode
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.blaykie.nativeapp.zotero.collectionTree

@Composable
internal fun LibraryScreen(state: LibraryState, controller: LibraryController, onSignIn: () -> Unit) {
    BackHandler(enabled = state.route !is LibraryRoute.Libraries) { controller.back() }
    when (state.route) {
        is LibraryRoute.Libraries -> LibrariesScreen(state, controller, onSignIn)
        is LibraryRoute.Root -> LibraryRootScreen(state, controller)
        is LibraryRoute.Node -> LibraryNodeScreen(state, controller)
        is LibraryRoute.Detail -> ItemDetailScreen(state, controller)
    }
}

@Composable
private fun LibrariesScreen(state: LibraryState, controller: LibraryController, onSignIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(Modifier.fillMaxSize().padding(horizontal = Space.large)) {
        Text("Libraries", style = typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.medium))
        if (!state.signedIn) {
            Text("Sign in with Zotero or add an API key to browse your library.", style = typography.bodyLarge)
            Spacer(Modifier.height(Space.medium))
            AppPrimaryButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in or enter a key") }
            return@Column
        }
        LibraryRow("My Library", null) { controller.openLibrary(ZoteroLibrary.Personal) }
        Spacer(Modifier.height(Space.large))
        Text("Group Libraries", style = typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.small))
        groupsMessage(state.groupsLoaded, state.groups, state.groupsError)?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = typography.bodySmall, color = colors.onSurfaceVariant)
                if (state.groupsError != null) TextButton(onClick = controller::retryGroups) { Text("Retry") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.medium),
            contentPadding = PaddingValues(vertical = Space.small)) {
            items(state.groups, key = { it.id }) { group ->
                LibraryRow(group.name, group.numItems?.let { "$it items" }) {
                    controller.openLibrary(ZoteroLibrary.Group(group))
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(name: String, detail: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = AppShapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), "Open", tint = colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryRootScreen(state: LibraryState, controller: LibraryController) {
    val library = state.library ?: return
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var menuOpen by remember { mutableStateOf(false) }
    val title = when (library) {
        is ZoteroLibrary.Personal -> "My Library"
        is ZoteroLibrary.Group -> library.group.name
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = { controller.back() }) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") }
        }, actions = {
            IconButton(onClick = { controller.openNode(BrowseNode.All, "All Items") }) {
                Icon(painterResource(R.drawable.ic_search), "Search all items")
            }
            TextButton(onClick = { controller.openNode(BrowseNode.All, "All Items"); controller.requestTags() }) { Text("Tags") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more), "Sort") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    BrowseSort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text("Sort by ${sort.label}") }, onClick = {
                            menuOpen = false
                            controller.setSort(sort, if (sort == BrowseSort.Title || sort == BrowseSort.Creator) false else true)
                            controller.openNode(BrowseNode.All, "All Items")
                        })
                    }
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.large),
            contentPadding = PaddingValues(vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            item { LibraryRow("All Items", null) { controller.openNode(BrowseNode.All, "All Items") } }
            if (state.collectionsError != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.collectionsError ?: "", Modifier.weight(1f), style = typography.bodyMedium)
                    TextButton(onClick = controller::retryCollections) { Text("Retry") }
                }
            } else if (!state.collectionsLoaded) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val roots = collectionTree(state.collections)
                if (roots.isEmpty()) item {
                    Text("No collections", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                items(roots, key = { it.collection.key }) { node ->
                    CollectionRootRow(state, controller, node)
                }
            }
            if (library is ZoteroLibrary.Personal && state.publicationsAvailable) item {
                LibraryRow("My Publications", null) { controller.openNode(BrowseNode.Publications, "My Publications") }
            }
            item { LibraryRow("Trash", null) { controller.openNode(BrowseNode.Trash, "Trash") } }
        }
    }
}

@Composable
private fun CollectionRootRow(state: LibraryState, controller: LibraryController,
                              node: org.blaykie.nativeapp.zotero.CollectionNode) {
    val collection = node.collection
    val expanded = collection.key in state.expandedCollections
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                LibraryRow(collection.name, collection.numItems?.let { "$it items" }) { controller.openCollection(collection) }
            }
            if (node.children.isNotEmpty()) {
                IconButton(onClick = { controller.toggleExpanded(collection.key) }) {
                    Icon(painterResource(R.drawable.ic_chevron_right), if (expanded) "Collapse" else "Expand",
                        Modifier.rotate(if (expanded) 90f else 0f))
                }
            }
        }
        if (expanded) node.children.forEach { child ->
            Row {
                Spacer(Modifier.width(24.dp))
                Box(Modifier.weight(1f)) { CollectionRootRow(state, controller, child) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryNodeScreen(state: LibraryState, controller: LibraryController) {
    val route = state.route as? LibraryRoute.Node ?: return
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var tagsOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.openTagsRequested) { if (state.openTagsRequested > 0) tagsOpen = true }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size, state.endReached, state.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= state.items.size - 5) controller.loadMore() }
    }
    val subcollections = remember(state.collections, route.node) {
        (route.node as? BrowseNode.Collection)?.let { node ->
            state.collections.filter { it.parentKey == node.key }.sortedBy { it.name.lowercase() }
        } ?: emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(route.title) }, navigationIcon = {
            IconButton(onClick = { controller.back() }) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") }
        }, actions = {
            TextButton(onClick = { tagsOpen = true; controller.loadTags("") }) { Text("Tags") }
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(painterResource(R.drawable.ic_more), "Sort") }
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
        })
        Column(Modifier.padding(horizontal = Space.large)) {
            OutlinedTextField(value = state.query, onValueChange = controller::query,
                placeholder = { Text("Search here") }, singleLine = true, shape = CircleShape,
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
                Spacer(Modifier.weight(1f))
                Text("Sort: ${state.sort.label} ${if (state.descending) "↓" else "↑"}", style = typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (state.tag != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputChip(selected = true, onClick = { controller.setTag(null) },
                        label = { Text(state.tag) }, shape = CircleShape,
                        trailingIcon = { Icon(painterResource(R.drawable.ic_close), "Remove tag filter", Modifier.size(14.dp)) })
                }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.items.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(state.error ?: "", style = typography.bodyLarge)
                Spacer(Modifier.height(Space.medium))
                AppPrimaryButton(onClick = controller::retry) { Text("Retry") }
            }
            state.items.isEmpty() && subcollections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No items", style = typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = Space.large),
                contentPadding = PaddingValues(vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                if (subcollections.isNotEmpty()) {
                    item { Text("Subcollections", style = typography.titleSmall, fontWeight = FontWeight.Bold) }
                    items(subcollections, key = { "sub-" + it.key }) { collection ->
                        LibraryRow(collection.name, collection.numItems?.let { "$it items" }) { controller.openCollection(collection) }
                    }
                    if (state.items.isNotEmpty()) item { Text("Items", style = typography.titleSmall, fontWeight = FontWeight.Bold) }
                }
                items(state.items, key = { it.key }) { item ->
                    OutlinedCard(onClick = { controller.selectItem(item) }, modifier = Modifier.fillMaxWidth(), shape = AppShapes.medium,
                        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                        border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            ItemCardText(item)
                            if (route.node is BrowseNode.Trash) {
                                Text("Trash", style = typography.labelSmall, color = colors.error)
                            } else (route.library as? ZoteroLibrary.Group)?.let { group ->
                                Text(group.group.name, style = typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (state.items.isEmpty() && subcollections.isNotEmpty()) item {
                    Text("No items", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (state.loadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (state.error != null && state.items.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error ?: "", Modifier.weight(1f), style = typography.bodySmall)
                        TextButton(onClick = controller::loadMore) { Text("Retry") }
                    }
                }
            }
        }
    }
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
                    Text(state.tagsError ?: "", style = MaterialTheme.typography.bodyMedium)
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
    val route = state.route as? LibraryRoute.Detail ?: return
    val item = route.item
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val collectionNames = remember(state.collections) { state.collections.associate { it.key to it.name } }
    val fields = remember(item, collectionNames) { itemDetailFields(item, collectionNames) }
    val doi = remember(item) { doiText(item) }
    val link = remember(item) { externalLink(item) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Item details") }, navigationIcon = {
            IconButton(onClick = { controller.back() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), "Back")
            }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.large),
            contentPadding = PaddingValues(vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            (route.library as? ZoteroLibrary.Group)?.let { group ->
                item { Text(group.group.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
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
