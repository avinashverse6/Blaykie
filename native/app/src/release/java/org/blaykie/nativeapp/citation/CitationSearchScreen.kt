package org.blaykie.nativeapp.citation

import androidx.compose.runtime.Composable

@Composable
internal fun CitationSearchScreen(state: SearchState, controller: CitationSearchController, paragraph: String, onCancel: () -> Unit, onInsert: () -> Unit, onAddKey: () -> Unit) {
    CitationForm(state, controller, paragraph, onCancel, onInsert, onAddKey)
}
