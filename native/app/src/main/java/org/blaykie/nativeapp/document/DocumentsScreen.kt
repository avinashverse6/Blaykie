package org.blaykie.nativeapp.document

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.*
import org.blaykie.nativeapp.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

// Compare calendar dates, not elapsed 24-hour periods (including across DST).
internal fun editedDateLabel(timestamp: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
    fun day(millis: Long): Long {
        val local = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        return utc.timeInMillis / 86_400_000L
    }
    val days = day(now) - day(timestamp)
    return when (days) {
        0L -> "Edited today"
        1L -> "Edited yesterday"
        in 2L..6L -> "Edited $days days ago"
        else -> "Edited ${DateFormat.getDateInstance(DateFormat.SHORT).apply { timeZone = zone }.format(Date(timestamp))}"
    }
}
internal fun documentTintIndex(index: Int): Int = Math.floorMod(index, 3)

@Composable
internal fun DocumentsScreen(documents: List<StoredDocument>, message: String, onAdd: () -> Unit,
                             onOpen: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
                             textSize: TextSize, extraStep: HomeExtraStep? = null) {
    var menu by remember { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium),
        verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandLogo(36)
                    Spacer(Modifier.width(12.dp))
                    Text("Blaykie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
                Text("Cite from your phone.", style = MaterialTheme.typography.displaySmall)
                Text("Open a Word file, tap a paragraph, add your Zotero source.", style = MaterialTheme.typography.bodyLarge)
                Surface(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
                    color = colors.primary, contentColor = colors.onPrimary) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(16.dp), color = colors.onPrimary.copy(alpha = 0.18f)) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_document_plus), null, tint = colors.onPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Open a Word document", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            Text("Pick a .docx from your phone", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (message.isNotEmpty()) AppErrorState(message)
            }
        }
        if (documents.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                    BrandLogo(72)
                    Text("How Blaykie works", style = MaterialTheme.typography.titleLarge)
                    VerticalSteps(homeSteps(null, includePreview = true))
                }
            }
        } else {
            item { Text("Recent documents", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant) }
        }
        itemsIndexed(documents, key = { _, it -> it.id }) { index, document ->
            val tile = when (documentTintIndex(index)) {
                0 -> if (dark) Color(0xFF243468) else Color(0xFFE6ECFD)
                1 -> if (dark) Color(0xFF51421A) else Color(0xFFFFF1C6)
                else -> if (dark) Color(0xFF1B4838) else Color(0xFFDDF3E6)
            }
            val ink = when (documentTintIndex(index)) {
                0 -> if (dark) Color(0xFFDCE4FF) else Color(0xFF1F3FA8)
                1 -> if (dark) Color(0xFFFFE28B) else Color(0xFF685000)
                else -> if (dark) Color(0xFFA7E6C5) else Color(0xFF175A3B)
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, colors.outline), colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { onOpen(document.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = tile) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_document_outline), null, Modifier.size(24.dp), tint = ink)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.state.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                            val count = document.state.citations.size
                            Text(editedDateLabel(document.state.lastEditedAtMillis) + if (count > 0) " · $count ${if (count == 1) "citation" else "citations"}" else "",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = document.id }) { Icon(painterResource(R.drawable.ic_more), contentDescription = "More options") }
                        DropdownMenu(expanded = menu == document.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = null; name = document.state.displayName; renaming = document.id })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = null; deleting = document.id })
                        }
                    }
                }
            }
        }
    }
    documents.firstOrNull { it.id == deleting }?.let { document ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${document.state.displayName}?") },
            text = { Text("This removes Blaykie's working copy and saved citations. Your original file is not touched.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(document.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { id ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename document") }, text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; onRename(id, name.trim()) }) { Text("Rename") }
        }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}
