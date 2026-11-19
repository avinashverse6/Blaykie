package org.blaykie.nativeapp.document

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DocumentsScreen(documents: List<StoredDocument>, message: String, onAdd: () -> Unit,
                             onOpen: (String) -> Unit, onRename: (String, String) -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Documents", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Button(onClick = onAdd) { Text("Add document") }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        if (documents.isEmpty()) Text("No documents yet")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(documents, key = { it.id }) { document ->
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(document.id) }.padding(12.dp)) {
                            Text(document.state.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("Last edited ${dateFormat.format(Date(document.state.lastEditedAtMillis))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            TextButton(onClick = { menu = document.id }) { Text("More") }
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
    }
    renaming?.let { id ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename document") }, text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; onRename(id, name.trim()) }) { Text("Rename") }
        }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}
