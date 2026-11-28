package org.blaykie.nativeapp.citation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.docx.LocatorLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CitationOptionFields(state: SearchState, controller: CitationSearchController) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = state.page, onValueChange = controller::page,
                label = { Text("Locator") }, singleLine = true, shape = AppShapes.small, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { menuOpen = !menuOpen }, shape = CircleShape, modifier = Modifier.weight(.62f).heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                Text(state.options.label.title + if (menuOpen) " ▴" else " ▾")
            }
        }
        // Expand in the scroll flow rather than covering the locator or nearby fields.
        if (menuOpen) Surface(shape = AppShapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth()) {
                LocatorLabel.entries.forEach { label ->
                    DropdownMenuItem(text = { Text(label.title) }, onClick = { controller.label(label); menuOpen = false },
                        trailingIcon = if (state.options.label == label) { { Text("✓") } } else null)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.prefix, onValueChange = controller::prefix,
                label = { Text("Prefix") }, singleLine = true, shape = AppShapes.small, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state.options.suffix, onValueChange = controller::suffix,
                label = { Text("Suffix") }, singleLine = true, shape = AppShapes.small, modifier = Modifier.weight(1f))
        }
        if (state.style == "parenthetical") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Omit author", Modifier.weight(1f))
            Switch(checked = state.options.omitAuthor, onCheckedChange = controller::omitAuthor)
        }
    }
}
