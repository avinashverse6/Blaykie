package org.blaykie.nativeapp.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.BlaykieCatLogo
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.citation.citationTitleMaxLines
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.titleFieldByType

internal fun addFormSaveEnabled(titleSatisfied: Boolean, saving: Boolean) = titleSatisfied && !saving
internal fun creatorRoleSelected(row: ManualCreatorRow, role: String) = row.copy(role = role)
internal fun creatorNameModeSelected(row: ManualCreatorRow, single: Boolean) = row.copy(singleField = single)
internal enum class AddSuccessAction { VIEW_ITEM, ADD_ANOTHER, DONE }
internal fun routeAddSuccess(action: AddSuccessAction, view: () -> Unit, another: () -> Unit, done: () -> Unit) {
    when (action) { AddSuccessAction.VIEW_ITEM -> view(); AddSuccessAction.ADD_ANOTHER -> another(); AddSuccessAction.DONE -> done() }
}

@Composable
internal fun AddItemHeader(title: String, onClose: () -> Unit, onChangeType: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        if (onChangeType != null) TextButton(onClick = onChangeType, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change type") }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_close), "Close", Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
internal fun AddItemFooter(secondary: String, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit,
                           enabled: Boolean, busy: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = colors.outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSecondary, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(secondary) }
                Button(onClick = onPrimary, enabled = enabled && !busy, shape = CircleShape,
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp), elevation = null,
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = colors.onSurface.copy(alpha = .12f))) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface.copy(alpha = .38f)); Spacer(Modifier.width(8.dp)) }
                    Text(if (busy) "${if (primary == "Fetch details") "Looking up" else "Saving"}…" else primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun AddItemSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun CollapsibleItemSection(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit,
                                    content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (expanded) "▴" else "▾")
            }
            if (!expanded) Text("$count more ${if (count == 1) "field" else "fields"}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun AddFormField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
                          singleLine: Boolean = false, minLines: Int = 1, supportingText: @Composable (() -> Unit)? = null) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
        singleLine = singleLine, minLines = minLines, supportingText = supportingText)
}

@Composable
internal fun AddItemError(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer, contentColor = colors.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}

@Composable
internal fun AddDuplicateDialog(title: String, viaIsbn: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, shape = RoundedCornerShape(28.dp),
        title = { Text("Already in your library", style = MaterialTheme.typography.titleLarge) },
        text = { Text("This ${if (viaIsbn) "ISBN" else "DOI"} is already in your library: $title", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { OutlinedButton(onClick = onConfirm, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add anyway") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

@Composable
internal fun AddItemSuccess(savedKey: String, item: ZoteroItem?, onView: () -> Unit, onAnother: () -> Unit, onDone: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var lastCelebrated by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(savedKey) {
        if (lastCelebrated != savedKey) { lastCelebrated = savedKey; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val type = (item?.data?.get("itemType") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val title = ((item?.data?.get(titleFieldByType[type] ?: "title")) as? JsonPrimitive)?.contentOrNull.orEmpty()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BlaykieCatLogo(72.dp)
        Text("Added to My Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyLarge,
            maxLines = citationTitleMaxLines(title), overflow = TextOverflow.Ellipsis)
        Button(onClick = { routeAddSuccess(AddSuccessAction.VIEW_ITEM, onView, onAnother, onDone) }, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("View item") }
        OutlinedButton(onClick = { routeAddSuccess(AddSuccessAction.ADD_ANOTHER, onView, onAnother, onDone) }, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add another") }
        TextButton(onClick = { routeAddSuccess(AddSuccessAction.DONE, onView, onAnother, onDone) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
    }
}

private fun formTypeIcon(kind: Int): ImageVector = ImageVector.Builder("Item type $kind", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        when (kind) {
            0 -> { moveTo(6f, 3f); lineTo(15f, 3f); lineTo(19f, 7f); lineTo(19f, 21f); lineTo(6f, 21f); close(); moveTo(9f, 12f); lineTo(16f, 12f); moveTo(9f, 16f); lineTo(16f, 16f) }
            1 -> { moveTo(4f, 5f); curveTo(7f, 4f, 10f, 4f, 12f, 6f); curveTo(14f, 4f, 17f, 4f, 20f, 5f); lineTo(20f, 19f); curveTo(17f, 18f, 14f, 18f, 12f, 20f); curveTo(10f, 18f, 7f, 18f, 4f, 19f); close(); moveTo(12f, 6f); lineTo(12f, 20f) }
            2 -> { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close(); moveTo(9f, 4f); lineTo(9f, 20f); moveTo(12f, 9f); lineTo(16f, 9f) }
            3 -> { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close(); moveTo(3f, 9f); lineTo(21f, 9f); moveTo(6f, 7f); lineTo(7f, 7f) }
            4 -> { moveTo(2f, 9f); lineTo(12f, 4f); lineTo(22f, 9f); lineTo(12f, 14f); close(); moveTo(6f, 11f); lineTo(6f, 16f); curveTo(9f, 19f, 15f, 19f, 18f, 16f); lineTo(18f, 11f) }
            5 -> { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); close(); moveTo(8f, 8f); lineTo(16f, 8f); moveTo(8f, 12f); lineTo(16f, 12f); moveTo(8f, 16f); lineTo(13f, 16f) }
            6 -> { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close(); moveTo(6f, 8f); lineTo(11f, 8f); lineTo(11f, 13f); lineTo(6f, 13f); close(); moveTo(14f, 8f); lineTo(18f, 8f); moveTo(14f, 12f); lineTo(18f, 12f); moveTo(6f, 16f); lineTo(18f, 16f) }
            else -> { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close(); moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 14f); lineTo(16f, 14f) }
        }
    }
}.build()
private val formTypeIcons = (0..7).map(::formTypeIcon)
internal fun formTypeIconIndex(type: String): Int = when (type) {
    "book" -> 1; "bookSection" -> 2; "webpage", "blogPost" -> 3; "thesis" -> 4
    "report" -> 5; "newspaperArticle", "magazineArticle" -> 6; "journalArticle", "document" -> 0; else -> 7
}
@Composable
internal fun TypeIconTile(type: String) {
    val colors = MaterialTheme.colorScheme
    val index = formTypeIconIndex(type)
    val tile = when (index % 3) { 0 -> colors.primaryContainer; 1 -> colors.secondary.copy(alpha = .24f); else -> colors.surfaceVariant }
    Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = tile, contentColor = colors.onSurface) {
        Box(contentAlignment = Alignment.Center) { Icon(formTypeIcons[index], null, Modifier.size(24.dp)) }
    }
}
