package org.blaykie.nativeapp.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.BottomSheetShape
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
    var optionalOpen: Boolean by mutableStateOf(false)
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
        moreOpen = false; optionalOpen = false; showAllCreators = false; formInitialised = false
    }

    fun backToChooser() {
        itemType = null
        fields.clear(); creators.clear(); pasteRows.clear()
        pasteText = ""; pasteOpen = false; optionalOpen = false; formInitialised = false
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
        moreOpen = false; optionalOpen = false; showAllCreators = false; formInitialised = false
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
            add(if (form.optionalOpen) "1" else "0")
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
            if (index < saved.size) form.optionalOpen = next() == "1"
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
    var discardForChangeType by rememberSaveable { mutableStateOf(false) }
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

    ModalBottomSheet(onDismissRequest = { requestClose() }, shape = BottomSheetShape,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            val heading = if (manual.savedKey != null) "Added to My Library" else if (form.itemType == null) "Add manually"
                else "New ${schema?.label ?: form.typeLabel.ifBlank { humaniseSchemaName(form.itemType.orEmpty()) }}"
            AddItemHeader(heading, onClose = { requestClose() },
                onChangeType = if (manual.savedKey == null && form.itemType != null) ({ requestChangeType() }) else null)
            when {
                manual.savedKey != null -> AddItemSuccess(manual.savedKey, manual.savedItem,
                    onView = { form.reset(); controller.viewManualSavedItem() },
                    onAnother = { form.reset(); controller.addAnotherManual() }, onDone = { closeClean() })
                form.itemType == null -> {
                    TypeChooserContent(state, form, onPick = { info -> form.startType(info) }, modifier = Modifier.weight(1f))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TextButton(onClick = { requestClose() }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Text("Cancel") }
                }
                else -> ManualFormContent(state, controller, form, schema, onCancel = { requestClose() })
            }
        }
    }
    if (showDiscard) {
        AlertDialog(
            shape = RoundedCornerShape(28.dp), onDismissRequest = { showDiscard = false },
            title = { Text("Discard this item?") },
            text = { Text("The details you entered will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    if (discardForChangeType) form.backToChooser() else closeClean()
                }) { Text("Discard") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDiscard = false }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") }
            })
    }
}

@Composable
private fun TypeChooserContent(state: LibraryState, form: ManualFormState, onPick: (ItemTypeInfo) -> Unit, modifier: Modifier = Modifier) {
    val manual = state.manual
    val (common, more) = remember(manual.types) { groupedTypes(manual.types) }
    val query = form.typeQuery.trim().lowercase()
    fun matches(info: ItemTypeInfo): Boolean = query.isEmpty() || info.label.lowercase().contains(query)
    val commonShown = common.filter { matches(it) }
    val moreShown = more.filter { matches(it) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (manual.usingBasicTypes) item { Text("Showing basic types", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedTextField(value = form.typeQuery, onValueChange = { form.typeQuery = it },
                placeholder = { Text("Search types") }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp))
        }
        if (manual.typesLoading && manual.types.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (commonShown.isNotEmpty()) {
            item { Text("Common", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(commonShown, key = { it.type }) { info -> TypeRow(info, commonTypeHints[info.type], onPick) }
        }
        if (moreShown.isNotEmpty()) {
            item { Text("More types", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(moreShown, key = { it.type }) { info -> TypeRow(info, null, onPick) }
        }
        if (commonShown.isEmpty() && moreShown.isEmpty() && manual.types.isNotEmpty()) item {
            Text("No types match.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TypeRow(info: ItemTypeInfo, hint: String?, onPick: (ItemTypeInfo) -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = { onPick(info) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outline)) {
        Row(Modifier.padding(if (hint != null) 14.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hint != null) {
                TypeIconTile(info.type)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(info.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = colors.onSurfaceVariant)
        }
    }
}

private fun isDateLike(field: SchemaField): Boolean =
    field.name == "date" || field.baseField == "date" || knownBaseFields[field.name] == "date"

@Composable
private fun ManualFormContent(state: LibraryState, controller: LibraryController, form: ManualFormState,
                              schema: TypeSchema?, onCancel: () -> Unit) {
    val manual = state.manual
    val type = form.itemType ?: return
    val label = schema?.label ?: form.typeLabel.ifBlank { humaniseSchemaName(type) }
    when {
        schema != null -> ManualFieldsForm(state, controller, form, schema, onCancel)
        manual.schemaLoadingType == type -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            AddItemFooter("Cancel", onCancel, "Save to Zotero", {}, false)
        }
        else -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { SchemaLoadFailed(controller, manual, type, label) }
            AddItemFooter("Cancel", onCancel, "Save to Zotero", {}, false)
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
        AddItemError(message) { controller.loadManualSchema(type) }
        TextButton(onClick = { controller.useBasicManualForm(type) },
            modifier = Modifier.fillMaxWidth()) { Text("Use basic form") }
        TextButton(onClick = { clipboard.setText(AnnotatedString(details)) },
            modifier = Modifier.fillMaxWidth()) { Text("Copy details") }
    }
}

@Composable
private fun ManualFieldEditor(field: SchemaField, form: ManualFormState) {
    val isAbstract = field.name == "abstractNote"
    AddFormField(value = form.fields[field.name] ?: "", onChange = { form.fields[field.name] = it }, label = field.label,
        supportingText = if (isDateLike(field)) ({ Text("Formats: 2021, 2021-05, 2021-05-14, May 2021, n.d.") }) else null,
        singleLine = !isAbstract, minLines = if (isAbstract) 3 else 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualFieldsForm(state: LibraryState, controller: LibraryController,
                             form: ManualFormState, schema: TypeSchema, onCancel: () -> Unit) {
    val manual = state.manual
    val ordered = remember(schema) { orderedFormFields(schema) }
    val titleField = remember(schema) { titleFieldOf(schema) }
    val restFields = remember(ordered, titleField) { ordered.main.filter { titleField == null || it.name != titleField.name } }
    val detailNames = setOf("volume", "issue", "pages", "numPages", "DOI", "ISBN", "url", "accessDate", "language", "abstractNote")
    val basics = restFields.takeWhile { it.name !in detailNames && !isDateLike(it) }
    val details = restFields.drop(basics.size)
    val collectionAvailable = manual.collectionKey != null
    val primaryRole = schema.primaryCreatorType()
    val titleBlank = !titleSatisfied(schema, form.fields)
    val dateBlank = schema.fields.any { isDateLike(it) && form.fields[it.name].isNullOrBlank() } && schema.fields.any { isDateLike(it) }
    val noCreators = form.creators.all { it.isBlank() }
    val optionalValues = ordered.optional.map { form.fields[it.name].orEmpty() }
    LaunchedEffect(schema.type, optionalValues) { if (optionalValues.any { it.isNotEmpty() }) form.optionalOpen = true }
    LaunchedEffect(form.pasteText) {
        val parsed = parsePastedCreators(form.pasteText)
        form.pasteRows.clear(); form.pasteRows.addAll(parsed)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "basics") {
                AddItemSection("Basics") {
                    if (titleField != null) ManualFieldEditor(titleField, form)
                    basics.forEach { ManualFieldEditor(it, form) }
                }
            }
            item(key = "creators") {
                AddItemSection("Creators") {
                    TextButton(onClick = { form.pasteOpen = !form.pasteOpen }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste several authors") }
                    if (form.pasteOpen) PasteSection(form, primaryRole)
                    val visibleCount = if (form.showAllCreators) form.creators.size else minOf(5, form.creators.size)
                    repeat(visibleCount) { index ->
                        CreatorRowEditor(index, form.creators[index], schema,
                            canMoveUp = index > 0, canMoveDown = index < form.creators.lastIndex,
                            onChange = { updated -> form.creators[index] = updated },
                            onRemove = { form.creators.removeAt(index) },
                            onMove = { delta ->
                                val target = index + delta
                                if (target in form.creators.indices) {
                                    val moved = form.creators.removeAt(index); form.creators.add(target, moved)
                                }
                            })
                    }
                    if (form.creators.size > 5 && !form.showAllCreators) TextButton(onClick = { form.showAllCreators = true }) { Text("Show all ${form.creators.size}") }
                    OutlinedButton(onClick = { form.creators += ManualCreatorRow(role = primaryRole) }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("+", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(8.dp)); Text("Add creator")
                    }
                    if (noCreators) Text("APA uses the title in place of an author; you can add an author later in Zotero.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (details.isNotEmpty()) item(key = "details") {
                AddItemSection("Details") { details.forEach { ManualFieldEditor(it, form) } }
            }
            if (ordered.optional.isNotEmpty()) item(key = "optional") {
                AddItemSection("Optional details") {
                    TextButton(onClick = { form.optionalOpen = !form.optionalOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (form.optionalOpen) "Hide optional details ▴" else "Optional details ▾", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    if (!form.optionalOpen) Text("${ordered.optional.size} more ${if (ordered.optional.size == 1) "field" else "fields"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (form.optionalOpen) ordered.optional.forEach { ManualFieldEditor(it, form) }
                }
            }
            if (ordered.more.isNotEmpty()) item(key = "more") {
                AddItemSection("More fields") {
                    TextButton(onClick = { form.moreOpen = !form.moreOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (form.moreOpen) "Hide more fields ▴" else "More fields ▾", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    if (!form.moreOpen) Text("${ordered.more.size} more ${if (ordered.more.size == 1) "field" else "fields"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("These fields need a recent Zotero; items using them may not show in older Zotero versions or mobile apps.",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (form.moreOpen) ordered.more.forEach { ManualFieldEditor(it, form) }
                }
            }
            item(key = "hints") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (titleField != null && titleBlank) Text("${titleField.label} is needed to save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dateBlank) Text("No date yet — you can add one later in Zotero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (collectionAvailable && form.inCollection) "Will be saved to My Library, in collection ${manual.collectionName}"
                        else "Will be saved to My Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (collectionAvailable) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = form.inCollection, role = Role.Switch, onValueChange = { form.inCollection = it }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Add to this collection", Modifier.weight(1f)); Switch(checked = form.inCollection, onCheckedChange = null)
                    }
                }
            }
            if (manual.error != null) item(key = "error") {
                AddItemError(manual.error) { controller.saveManualDraft(form.editedDraft(schema), form.inCollection && collectionAvailable) }
            }
        }
        AddItemFooter("Cancel", onCancel, "Save to Zotero",
            { controller.saveManualDraft(form.editedDraft(schema), form.inCollection && collectionAvailable) },
            addFormSaveEnabled(!titleBlank, manual.saving), manual.saving)
    }
    if (manual.duplicateTitle != null) AddDuplicateDialog(manual.duplicateTitle, manual.duplicateViaIsbn,
        onConfirm = { controller.confirmManualAddAnyway(form.editedDraft(schema), form.inCollection && collectionAvailable) },
        onCancel = controller::cancelManualDuplicate)
}

@Composable
private fun PasteSection(form: ManualFormState, primaryRole: String) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = form.pasteText, onValueChange = { form.pasteText = it },
            label = { Text("One author per line, or separated by semicolons") },
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), minLines = 3)
        val count = if (showAll) form.pasteRows.size else minOf(5, form.pasteRows.size)
        repeat(count) { index ->
            val row = form.pasteRows[index]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.singleField) row.name else listOf(row.lastName, row.firstName).filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    form.pasteRows[index] = if (row.singleField) {
                        val split = splitLastFirst(row.name)
                        if (split != null) row.copy(singleField = false, lastName = split.first, firstName = split.second, name = "")
                        else row.copy(singleField = false, lastName = row.name, name = "")
                    } else row.copy(singleField = true, name = listOf(row.lastName, row.firstName).filter { it.isNotBlank() }
                        .joinToString(", "), firstName = "", lastName = "")
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (row.singleField) "Single name" else "Person") }
                IconButton(onClick = { form.pasteRows.removeAt(index) }) { Icon(painterResource(R.drawable.ic_close), "Remove") }
            }
        }
        if (form.pasteRows.size > 5 && !showAll) TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all ${form.pasteRows.size}") }
        if (form.pasteRows.isNotEmpty()) AppPrimaryButton(onClick = {
            form.pasteRows.filter { !it.isBlank() }.forEach { form.creators += it.copy(role = primaryRole) }
            form.pasteOpen = false; form.pasteText = ""
        }, modifier = Modifier.fillMaxWidth()) { Text("Add ${form.pasteRows.size} creators") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorRowEditor(index: Int, row: ManualCreatorRow, schema: TypeSchema,
                             canMoveUp: Boolean, canMoveDown: Boolean,
                             onChange: (ManualCreatorRow) -> Unit, onRemove: () -> Unit,
                             onMove: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Creator ${index + 1}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(painterResource(R.drawable.ic_chevron_right), "Move up", Modifier.rotate(-90f)) }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(painterResource(R.drawable.ic_chevron_right), "Move down", Modifier.rotate(90f)) }
                IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_close), "Remove creator") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                schema.creatorTypes.forEach { info ->
                    FilterChip(selected = row.role == info.type, onClick = { onChange(creatorRoleSelected(row, info.type)) },
                        label = { Text(info.label) }, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val segmentWidth = maxOf(maxWidth, (360 * LocalDensity.current.fontScale).dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    SingleChoiceSegmentedButtonRow(Modifier.widthIn(min = segmentWidth).heightIn(min = 48.dp)) {
                        val segmentColors = SegmentedButtonDefaults.colors(activeContainerColor = colors.primaryContainer, activeContentColor = colors.onPrimaryContainer)
                        SegmentedButton(selected = !row.singleField, onClick = { onChange(creatorNameModeSelected(row, false)) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2, RoundedCornerShape(12.dp)), colors = segmentColors) { Text("Person") }
                        SegmentedButton(selected = row.singleField, onClick = { onChange(creatorNameModeSelected(row, true)) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2, RoundedCornerShape(12.dp)), colors = segmentColors) { Text("Single name or organisation") }
                    }
                }
            }
            if (row.singleField) {
                Text("Use this for an organisation such as Ministry of Rural Development, or for a person who has only one name.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedTextField(value = row.name, onValueChange = { onChange(row.copy(name = it)) }, label = { Text("Name") },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), singleLine = true)
                if (looksLikeLastFirst(row.name)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Looks like “Last, First”.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val split = splitLastFirst(row.name)
                            if (split != null) onChange(row.copy(singleField = false, lastName = split.first, firstName = split.second, name = ""))
                        }) { Text("Switch to Person") }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 420.dp && LocalDensity.current.fontScale < 1.2f
                    val lastField: @Composable (Modifier) -> Unit = { modifier ->
                        OutlinedTextField(value = row.lastName, onValueChange = { onChange(row.copy(lastName = it)) },
                            label = { Text("Last name") }, shape = RoundedCornerShape(12.dp), modifier = modifier.heightIn(min = 56.dp), singleLine = true)
                    }
                    val firstField: @Composable (Modifier) -> Unit = { modifier ->
                        OutlinedTextField(value = row.firstName, onValueChange = { onChange(row.copy(firstName = it)) },
                            label = { Text("First name or initials") }, shape = RoundedCornerShape(12.dp), modifier = modifier.heightIn(min = 56.dp), singleLine = true)
                    }
                    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lastField(Modifier.weight(1f)); firstField(Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lastField(Modifier.fillMaxWidth()); firstField(Modifier.fillMaxWidth())
                    }
                }
                val split = splitLastFirst(row.lastName)
                if (split != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Split into last name and first name?", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(row.copy(lastName = split.first,
                        firstName = listOf(split.second, row.firstName).filter { it.isNotBlank() }.joinToString(" "))) }) { Text("Split") }
                }
            }
        }
    }
}
