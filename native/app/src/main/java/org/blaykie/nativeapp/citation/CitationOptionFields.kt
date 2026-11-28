package org.blaykie.nativeapp.citation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.docx.LocatorLabel

internal val citationLocatorLabels = listOf(LocatorLabel.Page, LocatorLabel.Chapter, LocatorLabel.Paragraph, LocatorLabel.Section)

@Composable
internal fun CitationOptionFields(state: SearchState, controller: CitationSearchController) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citationLocatorLabels.forEach { label ->
                FilterChip(selected = state.options.label == label, onClick = { controller.label(label) },
                    label = { Text(label.title) }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer,
                        selectedLabelColor = colors.onPrimaryContainer))
            }
        }
        OutlinedTextField(value = state.page, onValueChange = controller::page,
            label = { Text("Locator") }, singleLine = true, shape = AppShapes.small,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.prefix, onValueChange = controller::prefix,
                label = { Text("Prefix") }, singleLine = true, shape = AppShapes.small, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state.options.suffix, onValueChange = controller::suffix,
                label = { Text("Suffix") }, singleLine = true, shape = AppShapes.small, modifier = Modifier.weight(1f))
        }
        // Narrative citations never use omit-author; preserve the existing rule.
        if (state.style == "parenthetical") Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = state.options.omitAuthor, role = Role.Switch, onValueChange = controller::omitAuthor),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Omit author", Modifier.weight(1f))
            Switch(checked = state.options.omitAuthor, onCheckedChange = null)
        }
    }
}
