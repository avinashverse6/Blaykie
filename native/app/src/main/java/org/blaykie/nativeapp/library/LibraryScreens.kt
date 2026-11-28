package org.blaykie.nativeapp.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.BlaykieCatLogo
import org.blaykie.nativeapp.BottomSheetShape
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.citation.citationTitleMaxLines
import org.blaykie.nativeapp.citation.groupsMessage
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.zotero.*

private val folderIcon = ImageVector.Builder("Folder", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12f, 7f); lineTo(21f, 7f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    }
}.build()
private val plusIcon = ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(12f, 4f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(20f, 12f)
    }
}.build()

@Composable
internal fun LibraryScreen(state: LibraryState, controller: LibraryController, onSignIn: () -> Unit,
                           onAllowEditing: () -> Unit = {}) {
    BackHandler(enabled = state.route !is LibraryRoute.Libraries) { controller.back() }
    when (state.route) {
        is LibraryRoute.Libraries -> LibrariesScreen(state, controller, onSignIn)
        is LibraryRoute.Root -> LibraryRootScreen(state, controller)
        is LibraryRoute.Node -> LibraryNodeScreen(state, controller)
        is LibraryRoute.Detail -> ItemDetailScreen(state, controller)
    }
    if (state.showPermissionSheet) PermissionSheet(state, onAllowEditing = { controller.dismissPermissionSheet(); onAllowEditing() },
        onAdvancedKey = { controller.dismissPermissionSheet(); onSignIn() }, onDismiss = controller::dismissPermissionSheet)
    if (state.showAddMenu) AddMenuSheet(controller, onDismiss = controller::dismissAddMenu)
    if (state.add.open) AddByDoiSheet(state, controller, onDismiss = controller::closeAdd)
    if (state.manual.open) AddManuallySheet(state, controller)
}

@Composable
private fun LibraryHeader(title: String, onBack: (() -> Unit)? = null, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") }
        Text(title, Modifier.weight(1f).padding(horizontal = if (onBack == null) 8.dp else 0.dp),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        if (onAdd != null) IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
            Surface(Modifier.size(46.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Box(contentAlignment = Alignment.Center) { Icon(plusIcon, "Add an item", Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryIconTile(index: Int, size: Int) {
    val colors = MaterialTheme.colorScheme
    val tint = when (index % 3) { 0 -> colors.primaryContainer; 1 -> colors.secondary.copy(alpha = 0.24f); else -> colors.tertiaryContainer }
    Surface(Modifier.size(size.dp), shape = RoundedCornerShape(14.dp), color = tint, contentColor = colors.onSurface) {
        Box(contentAlignment = Alignment.Center) { Icon(folderIcon, null, Modifier.size(24.dp)) }
    }
}

@Composable
private fun LibraryRow(name: String, detail: String?, index: Int = 0, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outline)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryIconTile(index, 48)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp)); Text("Loading…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BlaykieCatLogo(72.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibrariesScreen(state: LibraryState, controller: LibraryController, onSignIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Library")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.signedIn) {
                item { Text("Sign in with Zotero or add an API key to browse your library.") }
                item { AppPrimaryButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in or enter a key") } }
            } else {
                item { LibraryRow("My Library", null) { controller.openLibrary(ZoteroLibrary.Personal) } }
                item { Text("Group Libraries", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant) }
                if (!state.groupsLoaded && state.groupsError == null) item { LoadingState(Modifier.fillMaxWidth()) }
                if (state.groupsError != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(groupsMessage(state.groupsLoaded, state.groups, state.groupsError).orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = controller::retryGroups) { Text("Retry") }
                    }
                }
                if (state.groupsLoaded && state.groups.isEmpty()) item {
                    EmptyState(groupsMessage(true, state.groups, null).orEmpty(), Modifier.fillMaxWidth())
                }
                itemsIndexed(state.groups, key = { _, group -> group.id }) { index, group ->
                    LibraryRow(group.name, group.numItems?.let { "$it items" }, index + 1) { controller.openLibrary(ZoteroLibrary.Group(group)) }
                }
            }
        }
    }
}

@Composable
private fun LibraryGridTile(tile: LibraryTile, index: Int, controller: LibraryController, expanded: Set<String>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = when (tile) {
        LibraryTile.All -> "All Items"
        LibraryTile.Publications -> "My Publications"
        LibraryTile.Trash -> "Trash"
        is LibraryTile.Collection -> tile.node.collection.name
    }
    val detail = (tile as? LibraryTile.Collection)?.node?.collection?.numItems?.let { "$it items" }
    val onClick: () -> Unit = when (tile) {
        LibraryTile.All -> { { controller.openNode(BrowseNode.All, "All Items") } }
        LibraryTile.Publications -> { { controller.openNode(BrowseNode.Publications, "My Publications") } }
        LibraryTile.Trash -> { { controller.openNode(BrowseNode.Trash, "Trash") } }
        is LibraryTile.Collection -> { { controller.openCollection(tile.node.collection) } }
    }
    OutlinedCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LibraryIconTile(index, 40)
                Spacer(Modifier.weight(1f))
                if (tile is LibraryTile.Collection && tile.node.children.isNotEmpty()) {
                    val isExpanded = tile.node.collection.key in expanded
                    IconButton(onClick = { controller.toggleExpanded(tile.node.collection.key) }) {
                        Text(if (isExpanded) "▴" else "▾", color = colors.onSurfaceVariant)
                    }
                }
            }
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = if (citationTitleMaxLines(name) == Int.MAX_VALUE) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryRootScreen(state: LibraryState, controller: LibraryController) {
    val library = state.library ?: return
    var collectionsOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    val title = when (library) { is ZoteroLibrary.Personal -> "My Library"; is ZoteroLibrary.Group -> library.group.name }
    val roots = remember(state.collections) { collectionTree(state.collections) }
    val tiles = libraryRootTiles(library, roots, state.publicationsAvailable, state.expandedCollections)
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(title, onBack = { controller.back() }, onAdd = if (libraryAddVisible(state.route)) controller::addTapped else null)
        LibrarySearch(state.query, controller::query, onSearch = { controller.openNode(BrowseNode.All, "All Items") },
            onClear = { controller.query("") })
        LibraryFilterChips(state, onCollections = { collectionsOpen = true }, onClearCollection = {},
            onTags = { controller.openNode(BrowseNode.All, "All Items"); controller.requestTags() },
            onClearTag = { controller.setTag(null) }, onSort = { sortOpen = true })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(tiles.chunked(2)) { row, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    pair.forEachIndexed { column, tile -> LibraryGridTile(tile, row * 2 + column, controller, state.expandedCollections, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (state.collectionsError != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.collectionsError, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = controller::retryCollections) { Text("Retry") }
                }
            } else if (!state.collectionsLoaded) item { LoadingState(Modifier.fillMaxWidth()) }
            else if (roots.isEmpty()) item { EmptyState("No collections", Modifier.fillMaxWidth()) }
        }
    }
    if (collectionsOpen) CollectionsSheet(state, controller) { collectionsOpen = false }
    if (sortOpen) SortSheet(state, onDismiss = { sortOpen = false }, onSort = { sort, descending ->
        controller.setSort(sort, descending); controller.openNode(BrowseNode.All, "All Items")
    })
}

@Composable
private fun LibrarySearch(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(value = query, onValueChange = onQuery, placeholder = { Text("Search this library") },
        singleLine = true, shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.large).heightIn(min = 50.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        leadingIcon = { IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), "Search") } },
        trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = onClear) { Icon(painterResource(R.drawable.ic_close), "Clear search") } } } else null)
}

@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, onClick: () -> Unit, onClear: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Crossfade(targetState = selected, animationSpec = tween(150), label = "Filter selection") { active ->
        FilterChip(selected = active, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
            label = { Text(label) },
            trailingIcon = if (active && onClear != null) {
                { IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_close), "Clear $label", Modifier.size(16.dp)) } }
            } else { { Text("▾", style = MaterialTheme.typography.labelMedium) } },
            colors = FilterChipDefaults.filterChipColors(containerColor = colors.surface, selectedContainerColor = colors.primaryContainer,
                selectedLabelColor = colors.onPrimaryContainer))
    }
}

@Composable
private fun LibraryFilterChips(state: LibraryState, onCollections: () -> Unit, onClearCollection: () -> Unit,
                               onTags: () -> Unit, onClearTag: () -> Unit, onSort: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.large),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryFilterChip("Collections", libraryCollectionActive(state.node), onCollections, onClearCollection)
        LibraryFilterChip(if (state.tag == null) "Tags" else "Tags: ${state.tag}", libraryTagActive(state.tag), onTags, onClearTag)
        LibraryFilterChip(librarySortLabel(state.sort), false, onSort)
    }
}

@Composable
private fun LibraryNodeScreen(state: LibraryState, controller: LibraryController) {
    val route = state.route as? LibraryRoute.Node ?: return
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var collectionsOpen by rememberSaveable { mutableStateOf(false) }
    var tagsOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
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
        LibraryHeader(route.title, onBack = { controller.back() }, onAdd = if (libraryAddVisible(route)) controller::addTapped else null)
        LibrarySearch(state.query, controller::query, controller::search, onClear = { controller.query(""); controller.search() })
        LibraryFilterChips(state, onCollections = { collectionsOpen = true }, onClearCollection = { controller.openNode(BrowseNode.All, "All Items") },
            onTags = { tagsOpen = true; controller.loadTags("") }, onClearTag = { controller.setTag(null) }, onSort = { sortOpen = true })
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.large).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.everything, onCheckedChange = controller::setEverything)
            Spacer(Modifier.width(8.dp))
            Text("Search everything", Modifier.weight(1f), style = typography.bodyMedium)
            Text(if (state.descending) "↓" else "↑", color = colors.onSurfaceVariant)
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState() }
            state.error != null && state.items.isEmpty() -> Column(Modifier.fillMaxSize().padding(Space.large),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(state.error, style = typography.bodyLarge)
                Spacer(Modifier.height(Space.medium)); AppPrimaryButton(onClick = controller::retry) { Text("Retry") }
            }
            state.items.isEmpty() && subcollections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("No items") }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (subcollections.isNotEmpty()) {
                    item { Text("Subcollections", style = typography.titleSmall, fontWeight = FontWeight.Bold) }
                    items(subcollections, key = { "sub-" + it.key }) { collection ->
                        LibraryRow(collection.name, collection.numItems?.let { "$it items" }) { controller.openCollection(collection) }
                    }
                    if (state.items.isNotEmpty()) item { Text("Items", style = typography.titleSmall, fontWeight = FontWeight.Bold) }
                }
                items(state.items, key = { it.key }) { item ->
                    OutlinedCard(onClick = { controller.selectItem(item) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outline)) {
                        Column(Modifier.padding(14.dp)) {
                            ItemCardText(item)
                            if (route.node is BrowseNode.Trash) Text("Trash", style = typography.labelSmall, color = colors.error)
                            else (route.library as? ZoteroLibrary.Group)?.let { Text(it.group.name, style = typography.labelSmall, color = colors.onSurfaceVariant) }
                        }
                    }
                }
                if (state.items.isEmpty() && subcollections.isNotEmpty()) item { EmptyState("No items", Modifier.fillMaxWidth()) }
                if (state.loadingMore) item { LoadingState(Modifier.fillMaxWidth()) }
                if (state.error != null && state.items.isNotEmpty()) item {
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
    if (sortOpen) SortSheet(state, onDismiss = { sortOpen = false }, onSort = controller::setSort)
}

@Composable
private fun ItemCardText(item: ZoteroItem) {
    val citation = remember(item) { item.citationItem() }
    val authors = citation.authors.joinToString(", ") { it.last }.ifBlank { "Unknown author" }
    val venue = citation.publicationTitle.ifBlank { citation.publisher.ifBlank { citation.bookTitle } }
    Text(citation.title.ifBlank { "[No title]" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        maxLines = citationTitleMaxLines(citation.title), overflow = TextOverflow.Ellipsis)
    Text("$authors · ${CitationFormatting.displayYear(citation)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (venue.isNotBlank()) Text(venue, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionsSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    val nodes = remember(state.collections) { flattenTree(collectionTree(state.collections)) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.large).padding(bottom = Space.large)) {
            Text("Collections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (state.collectionsError != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.collectionsError, Modifier.weight(1f))
                    TextButton(onClick = controller::retryCollections) { Text("Retry") }
                }
            } else if (!state.collectionsLoaded) LoadingState(Modifier.fillMaxWidth())
            else if (nodes.isEmpty()) EmptyState("No collections", Modifier.fillMaxWidth())
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(nodes, key = { it.first.key }) { (collection, depth) ->
                    SheetChoice(collection.name, (state.node as? BrowseNode.Collection)?.key == collection.key,
                        Modifier.padding(start = (depth.coerceAtMost(4) * 16).dp)) {
                        onDismiss(); controller.openCollection(collection)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(state: LibraryState, onDismiss: () -> Unit, onSort: (BrowseSort, Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.large).padding(bottom = Space.large)) {
            Text("Sort", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(BrowseSort.entries) { sort ->
                    SheetChoice(sort.label, state.sort == sort) {
                        onDismiss(); onSort(sort, sort != BrowseSort.Title && sort != BrowseSort.Creator)
                    }
                }
                item { HorizontalDivider() }
                item { SheetChoice("Ascending ↑", !state.descending) { onDismiss(); onSort(state.sort, false) } }
                item { SheetChoice("Descending ↓", state.descending) { onDismiss(); onSort(state.sort, true) } }
            }
        }
    }
}

@Composable
private fun SheetChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    var tagQuery by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.large).padding(bottom = Space.large)) {
            Text("Tags", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Space.medium))
            OutlinedTextField(value = tagQuery, onValueChange = { tagQuery = it; controller.loadTags(it) },
                placeholder = { Text("Search tags") }, singleLine = true, shape = CircleShape, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.small))
            when {
                state.tagsError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.tagsError, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.loadTags(tagQuery) }) { Text("Retry") }
                }
                state.tagsLoading && state.tags.isEmpty() -> LoadingState(Modifier.fillMaxWidth())
                state.tags.isEmpty() -> EmptyState("No tags", Modifier.fillMaxWidth())
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (state.tag != null) item { SheetChoice("Clear tag filter", false) { controller.setTag(null); onDismiss() } }
                    items(state.tags, key = { it.name }) { tag ->
                        SheetChoice(tag.name + (tag.numItems?.let { " ($it)" } ?: ""), state.tag == tag.name) {
                            controller.setTag(tag.name); onDismiss()
                        }
                    }
                    if (!state.tagsEnd) item {
                        TextButton(onClick = { controller.loadMoreTags(tagQuery) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (state.tagsLoading) "Loading…" else "Load more tags")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val title = fields.firstOrNull { it.label == "Title" }?.value
    val type = fields.firstOrNull { it.label == "Type" }?.value
    val abstract = fields.firstOrNull { it.label == "Abstract" }?.value
    val tags = remember(item) {
        (item.data["tags"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    var abstractOverflow by remember(item.key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Item details", onBack = { controller.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (route.library as? ZoteroLibrary.Group)?.let { group -> item {
                Text(group.group.name, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            } }
            if (title != null) item { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            if (type != null) item { Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Text(type, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            } }
            val mainFields = fields.filter { it.label !in setOf("Title", "Type", "Abstract", "Tags") }
            if (mainFields.isNotEmpty()) item {
                OutlinedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                    border = BorderStroke(1.dp, colors.outline), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        mainFields.forEach { field ->
                            Column {
                                Text(field.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                if (field.label == "DOI" || field.label == "URL") SelectionContainer { Text(field.value, style = MaterialTheme.typography.bodyLarge) }
                                else Text(field.value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) item {
                Column {
                    Text("Tags", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag -> Surface(shape = CircleShape, color = colors.surfaceVariant) {
                            Text(tag, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
            }
            if (abstract != null) item {
                OutlinedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                    border = BorderStroke(1.dp, colors.outline), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Abstract", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(abstract, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 6,
                            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) abstractOverflow = it.hasVisualOverflow })
                        if (abstractOverflow || expanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (expanded) "Show less" else "Show more")
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (doi != null) OutlinedButton(onClick = { clipboard.setText(AnnotatedString(doi)) }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy DOI") }
                    if (link != null) OutlinedButton(onClick = { runCatching { uriHandler.openUri(link) } }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open link") }
                }
            }
        }
    }
}
