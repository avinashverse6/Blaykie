package org.blaykie.nativeapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.BottomSheetShape
import org.blaykie.nativeapp.BlaykieCatLogo
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.crossref.DraftCreator
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.crossref.supportedItemTypes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSheet(state: LibraryState, onAllowEditing: () -> Unit, onAdvancedKey: () -> Unit,
                             onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            AddItemHeader("Add items to Zotero", onDismiss)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BlaykieCatLogo(56.dp)
                Text("Add items to Zotero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(if (state.permissionManualKey)
                    "Your key does not allow editing. Create a key with 'Allow write access' on zotero.org, or sign in with Zotero."
                    else "To add items, Blaykie needs permission to edit your Zotero library. You can change or revoke this any time on zotero.org.",
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Button(onClick = if (state.permissionManualKey) onAdvancedKey else onAllowEditing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = CircleShape) {
                    Text(if (state.permissionManualKey) "Open the key screen" else "Allow editing")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddMenuSheet(controller: LibraryController, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape, containerColor = colors.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.large).padding(bottom = Space.large),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add to My Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            AddMenuRow("Add by DOI", "Look up a reference using its DOI", R.drawable.ic_search, controller::openAddByDoi)
            AddMenuRow("Add manually", "Enter the reference details yourself", R.drawable.ic_document_plus, controller::openAddManually)
        }
    }
}

@Composable
private fun AddMenuRow(title: String, hint: String, icon: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddByDoiSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    val add = state.add
    fun close() { if (!add.saving) onDismiss() }
    ModalBottomSheet(onDismissRequest = { close() }, shape = BottomSheetShape,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            AddItemHeader(when { add.savedKey != null -> "Added to My Library"; add.draft != null -> "Review and save"; else -> "Add by DOI" }, { close() })
            when {
                add.savedKey != null -> AddItemSuccess(add.savedKey, add.savedItem, controller::viewSavedItem, controller::addAnother, controller::closeAdd)
                add.draft != null -> ReviewContent(state, controller, onCancel = { close() })
                else -> EnterDoiContent(state, controller, onCancel = { close() })
            }
        }
    }
}

@Composable
private fun EnterDoiContent(state: LibraryState, controller: LibraryController, onCancel: () -> Unit) {
    val add = state.add
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = add.doi, onValueChange = controller::setDoi,
                label = { Text("DOI or doi.org link") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                trailingIcon = if (add.doi.isNotEmpty()) { { IconButton(onClick = { controller.setDoi("") }) {
                    Icon(painterResource(R.drawable.ic_close), "Clear DOI")
                } } } else null)
            TextButton(onClick = { clipboard.getText()?.text?.let(controller::setDoi) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste") }
            Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ⓘ", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    Text("Blaykie will look up this DOI at Crossref (api.crossref.org). Only the DOI is sent.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                }
            }
            if (add.error != null) AddItemError(add.error, controller::fetchDetails)
        }
        AddItemFooter("Cancel", onCancel, "Fetch details", controller::fetchDetails, add.doi.isNotBlank(), add.fetching)
    }
}

private val reviewCreatorsSaver = listSaver<List<DraftCreator>, String>(
    save = { rows -> rows.flatMap { listOf(it.firstName, it.lastName, it.name, it.role) } },
    restore = { values -> values.chunked(4).filter { it.size == 4 }.map { DraftCreator(it[0], it[1], it[2], it[3]) } },
)

@Composable
private fun ReviewField(value: String, label: String, minLines: Int = 1, onChange: (String) -> Unit) {
    AddFormField(value, label, onChange, minLines = minLines)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(state: LibraryState, controller: LibraryController, onCancel: () -> Unit) {
    val add = state.add
    val base = add.draft ?: return
    val f = base.fields
    var itemType by rememberSaveable(base) { mutableStateOf(base.itemType) }
    var title by rememberSaveable(base) { mutableStateOf(f["title"].orEmpty()) }
    var date by rememberSaveable(base) { mutableStateOf(f["date"].orEmpty()) }
    var publication by rememberSaveable(base) { mutableStateOf(f["publicationTitle"].orEmpty()) }
    var volume by rememberSaveable(base) { mutableStateOf(f["volume"].orEmpty()) }
    var issue by rememberSaveable(base) { mutableStateOf(f["issue"].orEmpty()) }
    var pages by rememberSaveable(base) { mutableStateOf(f["pages"].orEmpty()) }
    var doi by rememberSaveable(base) { mutableStateOf(f["DOI"].orEmpty()) }
    var url by rememberSaveable(base) { mutableStateOf(f["url"].orEmpty()) }
    var publisher by rememberSaveable(base) { mutableStateOf(f["publisher"].orEmpty()) }
    var abstract by rememberSaveable(base) { mutableStateOf(f["abstractNote"].orEmpty()) }
    var abstractOpen by rememberSaveable(base) { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var creators by rememberSaveable(base, stateSaver = reviewCreatorsSaver) { mutableStateOf(base.creators.toList()) }
    var inCollection by rememberSaveable(base) { mutableStateOf(true) }
    val collectionAvailable = add.collectionKey != null
    fun edited(): ItemDraft {
        val fields = LinkedHashMap(base.fields)
        fun set(name: String, value: String) { if (value.isBlank()) fields.remove(name) else fields[name] = value }
        set("title", title); set("date", date); set("publicationTitle", publication)
        set("volume", volume); set("issue", issue); set("pages", pages)
        set("DOI", doi); set("url", url); set("publisher", publisher); set("abstractNote", abstract)
        return ItemDraft(itemType, fields, creators.toMutableList(), typeGuessed = false)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (base.typeGuessed) item { Text("Crossref did not give a known type, so this is treated as a journal article. You can change the type below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                AddItemSection("Basics") {
                    Box {
                        OutlinedButton(onClick = { typeMenu = true }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(typeLabel(itemType)) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            supportedItemTypes.forEach { type -> DropdownMenuItem(text = { Text(typeLabel(type)) }, onClick = { itemType = type; typeMenu = false }) }
                        }
                    }
                    ReviewField(title, "Title") { title = it }
                }
            }
            item {
                AddItemSection("Creators") {
                    creators.forEachIndexed { index, creator ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(creator.displayName(), { value ->
                                creators = creators.toMutableList().apply { this[index] = creator.copy(firstName = "", lastName = "", name = value) }
                            }, label = { Text("Creator ${index + 1} (${creator.role})") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 56.dp))
                            IconButton(onClick = { creators = creators.toMutableList().apply { removeAt(index) } }) { Icon(painterResource(R.drawable.ic_close), "Remove creator") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { creators = creators + DraftCreator(role = "author") }) { Text("Add author") }
                        TextButton(onClick = { creators = creators + DraftCreator(role = "editor") }) { Text("Add editor") }
                    }
                }
            }
            item {
                AddItemSection("Details") {
                    ReviewField(date, "Date") { date = it }
                    ReviewField(volume, "Volume") { volume = it }
                    ReviewField(issue, "Issue") { issue = it }
                    ReviewField(publication, "Publication") { publication = it }
                    ReviewField(pages, "Pages") { pages = it }
                    ReviewField(doi, "DOI") { doi = it }
                    ReviewField(url, "URL") { url = it }
                    ReviewField(publisher, "Publisher") { publisher = it }
                    TextButton(onClick = { abstractOpen = !abstractOpen }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (abstractOpen) "Hide abstract ▴" else "Abstract ▾") }
                    if (abstractOpen) ReviewField(abstract, "Abstract", 3) { abstract = it }
                }
            }
            item {
                Column {
                    if (title.isBlank()) Text("Title is needed to save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (collectionAvailable && inCollection) "Will be saved to My Library, in collection ${add.collectionName}"
                        else "Will be saved to My Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (collectionAvailable) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = inCollection, role = Role.Switch, onValueChange = { inCollection = it }), verticalAlignment = Alignment.CenterVertically) {
                        Text("Add to this collection", Modifier.weight(1f)); Switch(checked = inCollection, onCheckedChange = null)
                    }
                }
            }
            if (add.error != null) item { AddItemError(add.error) { controller.saveDraft(edited(), inCollection && collectionAvailable) } }
        }
        AddItemFooter("Cancel", onCancel, "Save to Zotero", { controller.saveDraft(edited(), inCollection && collectionAvailable) },
            addFormSaveEnabled(title.isNotBlank(), add.saving), add.saving)
    }
    if (add.duplicateTitle != null) AddDuplicateDialog(add.duplicateTitle, add.duplicateViaIsbn,
        onConfirm = { controller.confirmAddAnyway(edited(), inCollection && collectionAvailable) }, onCancel = controller::cancelDuplicate)
}
