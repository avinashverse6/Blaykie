package org.blaykie.nativeapp.document

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import org.blaykie.nativeapp.AppErrorState
import org.blaykie.nativeapp.BrandChip
import org.blaykie.nativeapp.BrandPrimaryButton
import org.blaykie.nativeapp.HomeExtraStep
import org.blaykie.nativeapp.HomeHeader
import org.blaykie.nativeapp.HomeIconBadge
import org.blaykie.nativeapp.HomeLayout
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space
import org.blaykie.nativeapp.TextSize
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DocumentsScreen(documents: List<StoredDocument>, message: String, onAdd: () -> Unit,
                             onOpen: (String) -> Unit, onRename: (String, String) -> Unit, onSettings: () -> Unit,
                             textSize: TextSize, extraStep: HomeExtraStep? = null) {
    var menu by remember { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    if (documents.isEmpty()) {
        HomeLayout(textSize, message, extraStep, onOpen = onAdd, onSettings = onSettings)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.large, vertical = Space.medium),
        verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                HomeHeader(onSettings)
                BrandChip("APA 7 · ZOTERO")
                Text("Your documents", style = MaterialTheme.typography.titleLarge)
                BrandPrimaryButton("Open a Word document", onAdd, Modifier.fillMaxWidth())
                if (message.isNotEmpty()) AppErrorState(message)
            }
        }
        items(documents, key = { it.id }) { document ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(end = Space.small), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { onOpen(document.id) }.padding(Space.medium),
                        verticalAlignment = Alignment.CenterVertically) {
                        HomeIconBadge(R.drawable.ic_document_outline)
                        Spacer(Modifier.width(Space.medium))
                        Column(Modifier.weight(1f)) {
                            Text(document.state.displayName, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text("Last edited ${dateFormat.format(Date(document.state.lastEditedAtMillis))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = document.id }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu == document.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menu = null; name = document.state.displayName; renaming = document.id
                            })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { id ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename document") }, text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; onRename(id, name.trim()) }) { Text("Rename") }
        }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}
