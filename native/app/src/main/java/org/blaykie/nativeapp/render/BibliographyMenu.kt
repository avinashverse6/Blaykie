package org.blaykie.nativeapp.render

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.docx.BibliographyOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BibliographyMenu(options: BibliographyOptions, onChange: (BibliographyOptions) -> Boolean,
                              onPreview: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.ic_more), contentDescription = "Document menu")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Bibliography settings") }, onClick = { menu = false; settings = true; failed = false })
        }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bibliography settings", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add to end of document", Modifier.weight(1f))
                Switch(checked = options.enabled, onCheckedChange = { failed = !onChange(options.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "Add to end of document" })
            }
            OutlinedTextField(value = options.heading, onValueChange = { failed = !onChange(options.copy(heading = it)) },
                label = { Text("Heading") }, singleLine = true, enabled = options.enabled, modifier = Modifier.fillMaxWidth())
            if (failed) Text("Could not save bibliography options.")
            TextButton(onClick = { settings = false; onPreview() }) { Text("Bibliography preview") }
        }
    }
}
