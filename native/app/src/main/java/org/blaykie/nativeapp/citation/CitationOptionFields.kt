package org.blaykie.nativeapp.citation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.docx.LocatorLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CitationOptionFields(state: SearchState, controller: CitationSearchController) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            OutlinedTextField(value = state.page, onValueChange = controller::page,
                label = { Text("Locator") }, singleLine = true, modifier = Modifier.width(130.dp))
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text(state.options.label.title) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    LocatorLabel.entries.forEach { label ->
                        DropdownMenuItem(text = { Text(label.title) }, onClick = { controller.label(label); menuOpen = false })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.prefix, onValueChange = controller::prefix,
                label = { Text("Prefix") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state.options.suffix, onValueChange = controller::suffix,
                label = { Text("Suffix") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (state.style == "parenthetical") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Omit author", Modifier.weight(1f))
            Switch(checked = state.options.omitAuthor, onCheckedChange = controller::omitAuthor)
        }
    }
}
