package org.blaykie.nativeapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.crossref.DraftCreator
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.crossref.supportedItemTypes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSheet(state: LibraryState, onAllowEditing: () -> Unit, onAdvancedKey: () -> Unit,
                             onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large),
            verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            Text("Add items to Zotero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (state.permissionManualKey) {
                Text("Your key does not allow editing. Create a key with 'Allow write access' on zotero.org, or sign in with Zotero.",
                    style = MaterialTheme.typography.bodyLarge)
                AppPrimaryButton(onClick = onAdvancedKey, modifier = Modifier.fillMaxWidth()) { Text("Open the key screen") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            } else {
                Text("To add items, Blaykie needs permission to edit your Zotero library. You can change or revoke this any time on zotero.org.",
                    style = MaterialTheme.typography.bodyLarge)
                AppPrimaryButton(onClick = onAllowEditing, modifier = Modifier.fillMaxWidth()) { Text("Allow editing") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddMenuSheet(controller: LibraryController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large),
            verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            Text("Add to My Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { controller.openAddByDoi() }, modifier = Modifier.fillMaxWidth()) {
                Text("Add by DOI", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddByDoiSheet(state: LibraryState, controller: LibraryController, onDismiss: () -> Unit) {
    val add = state.add
    ModalBottomSheet(onDismissRequest = { if (!add.saving) onDismiss() }) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large)) {
            when {
                add.savedKey != null -> SavedContent(state, controller)
                add.draft != null -> ReviewContent(state, controller)
                else -> EnterDoiContent(state, controller)
            }
        }
    }
}

@Composable
private fun EnterDoiContent(state: LibraryState, controller: LibraryController) {
    val add = state.add
    Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        Text("Add by DOI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Blaykie will look up this DOI at Crossref (api.crossref.org). Only the DOI is sent.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = add.doi, onValueChange = controller::setDoi,
            label = { Text("DOI or doi.org link") }, singleLine = true, shape = CircleShape,
            modifier = Modifier.fillMaxWidth())
        if (add.error != null) Text(add.error, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        AppPrimaryButton(onClick = controller::fetchDetails, enabled = add.doi.isNotBlank() && !add.fetching,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (add.fetching) "Looking up…" else "Fetch details")
        }
        if (add.fetching) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun SavedContent(state: LibraryState, controller: LibraryController) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        Text("Added to My Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        AppPrimaryButton(onClick = controller::viewSavedItem, modifier = Modifier.fillMaxWidth()) { Text("View item") }
        TextButton(onClick = controller::addAnother, modifier = Modifier.fillMaxWidth()) { Text("Add another") }
        TextButton(onClick = controller::closeAdd, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(state: LibraryState, controller: LibraryController) {
    val add = state.add
    val base = add.draft ?: return
    var itemType by remember(base) { mutableStateOf(base.itemType) }
    var title by remember(base) { mutableStateOf(base.fields["title"].orEmpty()) }
    var date by remember(base) { mutableStateOf(base.fields["date"].orEmpty()) }
    var publication by remember(base) { mutableStateOf(base.fields["publicationTitle"].orEmpty()) }
    var volume by remember(base) { mutableStateOf(base.fields["volume"].orEmpty()) }
    var issue by remember(base) { mutableStateOf(base.fields["issue"].orEmpty()) }
    var pages by remember(base) { mutableStateOf(base.fields["pages"].orEmpty()) }
    var doi by remember(base) { mutableStateOf(base.fields["DOI"].orEmpty()) }
    var url by remember(base) { mutableStateOf(base.fields["url"].orEmpty()) }
    var publisher by remember(base) { mutableStateOf(base.fields["publisher"].orEmpty()) }
    var abstract by remember(base) { mutableStateOf(base.fields["abstractNote"].orEmpty()) }
    var abstractOpen by remember(base) { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val creators = remember(base) { mutableStateListOf<DraftCreator>().apply { addAll(base.creators) } }
    var inCollection by remember(base) { mutableStateOf(true) }
    val collectionAvailable = add.collectionKey != null
    fun edited(): ItemDraft {
        val fields = LinkedHashMap(base.fields)
        fun set(name: String, value: String) { if (value.isBlank()) fields.remove(name) else fields[name] = value }
        set("title", title); set("date", date); set("publicationTitle", publication)
        set("volume", volume); set("issue", issue); set("pages", pages)
        set("DOI", doi); set("url", url); set("publisher", publisher); set("abstractNote", abstract)
        return ItemDraft(itemType, fields, creators.toMutableList(), typeGuessed = false)
    }
    LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        item {
            Text("Review and save", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (base.typeGuessed) Text("Crossref did not give a known type, so this is treated as a journal article. You can change the type below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Box {
                OutlinedButton(onClick = { typeMenu = true }, shape = CircleShape) { Text(typeLabel(itemType)) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    supportedItemTypes.forEach { type ->
                        DropdownMenuItem(text = { Text(typeLabel(type)) }, onClick = { itemType = type; typeMenu = false })
                    }
                }
            }
        }
        item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
        itemsIndexed(creators) { index, creator ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(creator.displayName(), { value ->
                    creators[index] = creator.copy(firstName = "", lastName = "", name = value)
                }, label = { Text("Creator ${index + 1} (${creator.role})") }, modifier = Modifier.weight(1f))
                IconButton(onClick = { creators.removeAt(index) }) {
                    Icon(painterResource(R.drawable.ic_close), "Remove creator")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { creators += DraftCreator(role = "author") }) { Text("Add author") }
                TextButton(onClick = { creators += DraftCreator(role = "editor") }) { Text("Add editor") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = volume, onValueChange = { volume = it }, label = { Text("Volume") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = issue, onValueChange = { issue = it }, label = { Text("Issue") }, modifier = Modifier.weight(1f))
            }
        }
        item { OutlinedTextField(value = publication, onValueChange = { publication = it }, label = { Text("Publication") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = pages, onValueChange = { pages = it }, label = { Text("Pages") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = doi, onValueChange = { doi = it }, label = { Text("DOI") }, modifier = Modifier.weight(1f))
            }
        }
        item { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = publisher, onValueChange = { publisher = it }, label = { Text("Publisher") }, modifier = Modifier.fillMaxWidth()) }
        item {
            TextButton(onClick = { abstractOpen = !abstractOpen }) {
                Text(if (abstractOpen) "Hide abstract ▴" else "Abstract ▾")
            }
            if (abstractOpen) OutlinedTextField(value = abstract, onValueChange = { abstract = it }, label = { Text("Abstract") },
                modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
        item {
            Text(if (collectionAvailable && inCollection) "Will be saved to My Library, in collection ${add.collectionName}"
                else "Will be saved to My Library", style = MaterialTheme.typography.bodyMedium)
            if (collectionAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = inCollection, onCheckedChange = { inCollection = it })
                Spacer(Modifier.width(4.dp))
                Text("Add to this collection", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (add.duplicateTitle != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text("This DOI is already in your library: ${add.duplicateTitle}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrimaryButton(onClick = { controller.confirmAddAnyway(edited(), inCollection && collectionAvailable) }) { Text("Add anyway") }
                    TextButton(onClick = controller::cancelDuplicate) { Text("Cancel") }
                }
            }
        }
        if (add.error != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text(add.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { controller.saveDraft(edited(), inCollection && collectionAvailable) }) { Text("Retry") }
            }
        }
        item {
            AppPrimaryButton(onClick = { controller.saveDraft(edited(), inCollection && collectionAvailable) },
                enabled = title.isNotBlank() && !add.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (add.saving) "Saving…" else "Save to Zotero")
            }
        }
    }
}
