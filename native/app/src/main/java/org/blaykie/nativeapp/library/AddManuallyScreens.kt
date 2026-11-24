package org.blaykie.nativeapp.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.zotero.ItemTypeInfo
import org.blaykie.nativeapp.zotero.SchemaField
import org.blaykie.nativeapp.zotero.TypeSchema
import org.blaykie.nativeapp.zotero.humaniseSchemaName
import org.blaykie.nativeapp.zotero.knownBaseFields

/**
 * The Add manually form state. It lives in saved instance state only (never on
 * disk), so a half-filled form survives rotation and process death; the draft
 * itself is sent only when the user taps Save.
 */
internal class ManualFormState {
    var itemType: String? by mutableStateOf(null)
    var typeLabel: String by mutableStateOf("")
    var typeQuery: String by mutableStateOf("")
    val fields = mutableStateMapOf<String, String>()
    val creators = mutableStateListOf<ManualCreatorRow>()
    var formInitialised: Boolean by mutableStateOf(false)
    var moreOpen: Boolean by mutableStateOf(false)
    var showAllCreators: Boolean by mutableStateOf(false)
    var inCollection: Boolean by mutableStateOf(true)
    var pasteOpen: Boolean by mutableStateOf(false)
    var pasteText: String by mutableStateOf("")
    val pasteRows = mutableStateListOf<ManualCreatorRow>()

    fun startType(info: ItemTypeInfo) {
        itemType = info.type
        typeLabel = info.label
        fields.clear(); creators.clear(); pasteRows.clear()
        pasteText = ""; pasteOpen = false
        moreOpen = false; showAllCreators = false; formInitialised = false
    }

    fun backToChooser() {
        itemType = null
        fields.clear(); creators.clear(); pasteRows.clear()
        pasteText = ""; pasteOpen = false; formInitialised = false
    }

    fun ensureInitialised(schema: TypeSchema) {
        if (formInitialised) return
        defaultFieldValues(schema).forEach { (name, value) ->
            if (fields[name].isNullOrBlank()) fields[name] = value
        }
        if (creators.isEmpty()) creators += ManualCreatorRow(role = schema.primaryCreatorType())
        formInitialised = true
    }

    fun isDirty(schema: TypeSchema?): Boolean = manualFormDirty(schema, fields, creators)

    fun editedDraft(schema: TypeSchema): ItemDraft = buildManualDraft(schema, fields, creators)

    fun reset() {
        itemType = null; typeLabel = ""; typeQuery = ""
        fields.clear(); creators.clear(); pasteRows.clear()
        pasteText = ""; pasteOpen = false
        moreOpen = false; showAllCreators = false; formInitialised = false
        inCollection = true
    }
}

internal val manualFormSaver: Saver<ManualFormState, ArrayList<String>> = Saver(
    save = { form ->
        arrayListOf<String>().apply {
            add(form.itemType ?: ""); add(form.typeLabel); add(form.typeQuery)
            add(if (form.moreOpen) "1" else "0"); add(if (form.showAllCreators) "1" else "0")
            add(if (form.inCollection) "1" else "0"); add(if (form.formInitialised) "1" else "0")
            add(form.pasteText)
            add(form.fields.size.toString())
            form.fields.forEach { (name, value) -> add(name); add(value) }
            add(form.creators.size.toString())
            form.creators.forEach { row ->
                add(row.role); add(if (row.singleField) "1" else "0")
                add(row.firstName); add(row.lastName); add(row.name)
            }
        }
    },
    restore = { saved ->
        val form = ManualFormState()
        runCatching {
            var index = 0
            fun next(): String = saved[index++]
            form.itemType = next().ifEmpty { null }
            form.typeLabel = next(); form.typeQuery = next()
            form.moreOpen = next() == "1"; form.showAllCreators = next() == "1"
            form.inCollection = next() == "1"; form.formInitialised = next() == "1"
            form.pasteText = next()
            repeat(next().toInt()) { form.fields[next()] = next() }
            repeat(next().toInt()) {
                val role = next(); val single = next() == "1"
                val first = next(); val last = next(); val name = next()
                form.creators += ManualCreatorRow(role, single, first, last, name)
            }
        }
        form
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddManuallySheet(state: LibraryState, controller: LibraryController) {
    val manual = state.manual
    val form = rememberSaveable(saver = manualFormSaver) { ManualFormState() }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var discardForChangeType by remember { mutableStateOf(false) }
    val schema = form.itemType?.let { manual.schemas[it] }

    LaunchedEffect(manual.open) { if (manual.open) controller.loadManualTypes() }
    LaunchedEffect(form.itemType) { form.itemType?.let { controller.loadManualSchema(it) } }
    LaunchedEffect(schema) { if (schema != null) form.ensureInitialised(schema) }

    fun closeClean() {
        form.reset()
        controller.closeManual()
    }
    fun requestClose() {
        when {
            manual.savedKey != null -> closeClean()
            manual.saving -> Unit
            form.isDirty(schema) -> { discardForChangeType = false; showDiscard = true }
            else -> closeClean()
        }
    }
    fun requestChangeType() {
        if (form.isDirty(schema)) { discardForChangeType = true; showDiscard = true }
        else form.backToChooser()
    }

    ModalBottomSheet(onDismissRequest = { requestClose() }) {
        Column(Modifier.padding(horizontal = Space.large).padding(bottom = Space.large)) {
            when {
                manual.savedKey != null -> ManualSavedContent(
                    onViewItem = { form.reset(); controller.viewManualSavedItem() },
                    onAddAnother = { form.reset(); controller.addAnotherManual() },
                    onDone = { closeClean() })
                form.itemType == null -> TypeChooserContent(state, form,
                    onPick = { info -> form.startType(info) })
                else -> ManualFormContent(state, controller, form, schema,
                    onChangeType = { requestChangeType() })
            }
        }
    }
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard this item?") },
            text = { Text("The details you entered will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    if (discardForChangeType) form.backToChooser() else closeClean()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
            })
    }
}

@Composable
private fun ManualSavedContent(onViewItem: () -> Unit, onAddAnother: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        Text("Added to My Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        AppPrimaryButton(onClick = onViewItem, modifier = Modifier.fillMaxWidth()) { Text("View item") }
        TextButton(onClick = onAddAnother, modifier = Modifier.fillMaxWidth()) { Text("Add another") }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun TypeChooserContent(state: LibraryState, form: ManualFormState, onPick: (ItemTypeInfo) -> Unit) {
    val manual = state.manual
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        Text("Add manually", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (manual.usingBasicTypes) Text("Showing basic types",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = form.typeQuery, onValueChange = { v: String -> form.typeQuery = v },
            label = { Text("Search types") }, singleLine = true, shape = CircleShape,
            modifier = Modifier.fillMaxWidth())
        if (manual.typesLoading && manual.types.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        val (common, more) = remember(manual.types) { groupedTypes(manual.types) }
        val query = form.typeQuery.trim().lowercase()
        fun matches(info: ItemTypeInfo): Boolean = query.isEmpty() || info.label.lowercase().contains(query)
        val commonShown = common.filter { matches(it) }
        val moreShown = more.filter { matches(it) }
        LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            if (commonShown.isNotEmpty()) {
                item { Text("Common", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                items(commonShown, key = { it.type }) { info -> TypeRow(info, commonTypeHints[info.type], onPick) }
            }
            if (moreShown.isNotEmpty()) {
                item { Text("More types", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                items(moreShown, key = { it.type }) { info -> TypeRow(info, null, onPick) }
            }
            if (commonShown.isEmpty() && moreShown.isEmpty() && manual.types.isNotEmpty()) {
                item {
                    Text("No types match.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TypeRow(info: ItemTypeInfo, hint: String?, onPick: (ItemTypeInfo) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(info) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(info.label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), "Choose",
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun isDateLike(field: SchemaField): Boolean =
    field.name == "date" || field.baseField == "date" || knownBaseFields[field.name] == "date"

@Composable
private fun ManualFormContent(state: LibraryState, controller: LibraryController, form: ManualFormState,
                              schema: TypeSchema?, onChangeType: () -> Unit) {
    val manual = state.manual
    val type = form.itemType ?: return
    val label = schema?.label ?: form.typeLabel.ifBlank { humaniseSchemaName(type) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New $label", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onChangeType) { Text("Change type") }
        }
        when {
            schema != null -> ManualFieldsForm(state, controller, form, schema)
            // Only a load that is actually running shows a spinner. Any other missing
            // schema is shown as a recoverable error, so the sheet can never hang here.
            manual.schemaLoadingType == type -> Box(Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> SchemaLoadFailed(controller, manual, type, label)
        }
    }
}

/**
 * The recoverable failure state of a schema load: the stage and HTTP status, a Retry that
 * starts a fresh load, and "Use basic form" so the user can always continue offline.
 * "Copy details" copies only the stage, HTTP status and exception class name — never a key
 * or any item contents.
 */
@Composable
private fun SchemaLoadFailed(controller: LibraryController, manual: ManualState, type: String, label: String) {
    val message = manual.schemaError?.takeIf { manual.schemaErrorType == type }
        ?: "Could not load fields for $label: step schema. Check your connection and try again."
    val clipboard = LocalClipboardManager.current
    val details = controller.manualSchemaCopyDetails() ?: "stage=unknown; httpStatus=none; exception=none"
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        AppPrimaryButton(onClick = { controller.loadManualSchema(type) },
            modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        TextButton(onClick = { controller.useBasicManualForm(type) },
            modifier = Modifier.fillMaxWidth()) { Text("Use basic form") }
        TextButton(onClick = { clipboard.setText(AnnotatedString(details)) },
            modifier = Modifier.fillMaxWidth()) { Text("Copy details") }
    }
}

@Composable
private fun ManualFieldEditor(field: SchemaField, form: ManualFormState) {
    val isAbstract = field.name == "abstractNote"
    OutlinedTextField(
        value = form.fields[field.name] ?: "",
        onValueChange = { v: String -> form.fields[field.name] = v },
        label = { Text(field.label) },
        supportingText = if (isDateLike(field)) ({ Text("Formats: 2021, 2021-05, 2021-05-14, May 2021, n.d.") }) else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = !isAbstract,
        minLines = if (isAbstract) 3 else 1,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualFieldsForm(state: LibraryState, controller: LibraryController,
                             form: ManualFormState, schema: TypeSchema) {
    val manual = state.manual
    val ordered = remember(schema) { orderedFormFields(schema) }
    val titleField = remember(schema) { titleFieldOf(schema) }
    val restFields = remember(ordered, titleField) {
        ordered.main.filter { titleField == null || it.name != titleField.name }
    }
    val collectionAvailable = manual.collectionKey != null
    val primaryRole = schema.primaryCreatorType()
    val titleBlank = !titleSatisfied(schema, form.fields)
    val dateBlank = schema.fields.any { isDateLike(it) && form.fields[it.name].isNullOrBlank() } &&
        schema.fields.any { isDateLike(it) }
    val noCreators = form.creators.all { it.isBlank() }

    LaunchedEffect(form.pasteText) {
        val parsed = parsePastedCreators(form.pasteText)
        form.pasteRows.clear()
        form.pasteRows.addAll(parsed)
    }

    LazyColumn(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        if (titleField != null) item(key = "title") { ManualFieldEditor(titleField, form) }
        item(key = "creators-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Creators", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { form.pasteOpen = !form.pasteOpen }) { Text("Paste several authors") }
            }
        }
        if (form.pasteOpen) item(key = "paste") { PasteSection(form, primaryRole) }
        val visibleCount = if (form.showAllCreators) form.creators.size else minOf(5, form.creators.size)
        items(visibleCount, key = { index -> "creator-$index" }) { index ->
            CreatorRowEditor(index, form.creators[index], schema,
                canMoveUp = index > 0, canMoveDown = index < form.creators.lastIndex,
                onChange = { updated -> form.creators[index] = updated },
                onRemove = { form.creators.removeAt(index) },
                onMove = { delta ->
                    val target = index + delta
                    if (target in form.creators.indices) {
                        val moved = form.creators.removeAt(index)
                        form.creators.add(target, moved)
                    }
                })
        }
        if (form.creators.size > 5 && !form.showAllCreators) item(key = "show-all-creators") {
            TextButton(onClick = { form.showAllCreators = true }) {
                Text("Show all ${form.creators.size}")
            }
        }
        item(key = "add-creator") {
            TextButton(onClick = { form.creators += ManualCreatorRow(role = primaryRole) }) {
                Text("Add creator")
            }
        }
        if (noCreators) item(key = "no-creators-hint") {
            Text("APA uses the title in place of an author; you can add an author later in Zotero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(restFields, key = { it.name }) { field -> ManualFieldEditor(field, form) }
        if (ordered.more.isNotEmpty()) {
            item(key = "more-toggle") {
                Column {
                    TextButton(onClick = { form.moreOpen = !form.moreOpen }) {
                        Text(if (form.moreOpen) "Hide more fields ▴" else "More fields ▾")
                    }
                    Text("These fields need a recent Zotero; items using them may not show in older Zotero versions or mobile apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (form.moreOpen) items(ordered.more, key = { it.name }) { field -> ManualFieldEditor(field, form) }
        }
        item(key = "hints") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (titleField != null && titleBlank) Text("${titleField.label} is needed to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dateBlank) Text("No date yet — you can add one later in Zotero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (collectionAvailable && form.inCollection) "Will be saved to My Library, in collection ${manual.collectionName}"
                    else "Will be saved to My Library", style = MaterialTheme.typography.bodyMedium)
                if (collectionAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = form.inCollection, onCheckedChange = { form.inCollection = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Add to this collection", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (manual.duplicateTitle != null) item(key = "duplicate") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text("This ${if (manual.duplicateViaIsbn) "ISBN" else "DOI"} is already in your library: ${manual.duplicateTitle}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrimaryButton(onClick = {
                        controller.confirmManualAddAnyway(form.editedDraft(schema),
                            form.inCollection && collectionAvailable)
                    }) { Text("Add anyway") }
                    TextButton(onClick = controller::cancelManualDuplicate) { Text("Cancel") }
                }
            }
        }
        if (manual.error != null) item(key = "error") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text(manual.error ?: "", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    controller.saveManualDraft(form.editedDraft(schema), form.inCollection && collectionAvailable)
                }) { Text("Retry") }
            }
        }
        item(key = "save") {
            AppPrimaryButton(
                onClick = {
                    controller.saveManualDraft(form.editedDraft(schema), form.inCollection && collectionAvailable)
                },
                enabled = !titleBlank && !manual.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (manual.saving) "Saving…" else "Save to Zotero")
            }
        }
    }
}

@Composable
private fun PasteSection(form: ManualFormState, primaryRole: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        OutlinedTextField(
            value = form.pasteText, onValueChange = { v: String -> form.pasteText = v },
            label = { Text("One author per line, or separated by semicolons") },
            modifier = Modifier.fillMaxWidth(), minLines = 3)
        form.pasteRows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.singleField) row.name else listOf(row.lastName, row.firstName)
                    .filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    form.pasteRows[index] = if (row.singleField) {
                        val split = splitLastFirst(row.name)
                        if (split != null) row.copy(singleField = false, lastName = split.first,
                            firstName = split.second, name = "")
                        else row.copy(singleField = false, lastName = row.name, name = "")
                    } else {
                        row.copy(singleField = true,
                            name = listOf(row.lastName, row.firstName).filter { it.isNotBlank() }
                                .joinToString(", "), firstName = "", lastName = "")
                    }
                }) { Text(if (row.singleField) "Single name" else "Person") }
                IconButton(onClick = { form.pasteRows.removeAt(index) }) {
                    Icon(painterResource(R.drawable.ic_close), "Remove")
                }
            }
        }
        if (form.pasteRows.isNotEmpty()) {
            AppPrimaryButton(onClick = {
                form.pasteRows.filter { !it.isBlank() }.forEach { form.creators += it.copy(role = primaryRole) }
                form.pasteOpen = false
                form.pasteText = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Add ${form.pasteRows.size} creators") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CreatorRowEditor(index: Int, row: ManualCreatorRow, schema: TypeSchema,
                             canMoveUp: Boolean, canMoveDown: Boolean,
                             onChange: (ManualCreatorRow) -> Unit, onRemove: () -> Unit,
                             onMove: (Int) -> Unit) {
    var roleMenu by remember { mutableStateOf(false) }
    val roleLabel = schema.creatorTypes.firstOrNull { it.type == row.role }?.label ?: row.role
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Creator ${index + 1}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(painterResource(R.drawable.ic_chevron_right), "Move up", Modifier.rotate(-90f))
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(painterResource(R.drawable.ic_chevron_right), "Move down", Modifier.rotate(90f))
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), "Remove creator")
            }
        }
        Box {
            OutlinedButton(onClick = { roleMenu = true }, shape = CircleShape) { Text(roleLabel) }
            DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                schema.creatorTypes.forEach { info ->
                    DropdownMenuItem(text = { Text(info.label) }, onClick = {
                        onChange(row.copy(role = info.type)); roleMenu = false
                    })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !row.singleField, onClick = { onChange(row.copy(singleField = false)) },
                label = { Text("Person (last name, first name)") })
            FilterChip(selected = row.singleField, onClick = { onChange(row.copy(singleField = true)) },
                label = { Text("Single name or organisation (one field)") })
        }
        if (row.singleField) {
            Text("Use this for an organisation such as Ministry of Rural Development, or for a person who has only one name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = row.name, onValueChange = { v: String -> onChange(row.copy(name = v)) },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (looksLikeLastFirst(row.name)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Looks like “Last, First”.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val split = splitLastFirst(row.name)
                        if (split != null) onChange(row.copy(singleField = false, lastName = split.first,
                            firstName = split.second, name = ""))
                    }) { Text("Switch to Person") }
                }
            }
        } else {
            OutlinedTextField(value = row.lastName, onValueChange = { v: String -> onChange(row.copy(lastName = v)) },
                label = { Text("Last name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = row.firstName, onValueChange = { v: String -> onChange(row.copy(firstName = v)) },
                label = { Text("First name or initials") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val split = splitLastFirst(row.lastName)
            if (split != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Split into last name and first name?", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        onChange(row.copy(lastName = split.first,
                            firstName = listOf(split.second, row.firstName)
                                .filter { it.isNotBlank() }.joinToString(" ")))
                    }) { Text("Split") }
                }
            }
        }
        HorizontalDivider()
    }
}
