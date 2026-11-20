package org.blaykie.nativeapp.citation

import androidx.compose.runtime.Composable
import org.blaykie.nativeapp.citation.style.citationPreviewNote

@Composable
internal fun CitationSearchScreen(state: SearchState, controller: CitationSearchController, paragraph: String, onCancel: () -> Unit, onInsert: () -> Unit, onAddKey: () -> Unit, cslSelected: Boolean = false, restriction: String? = null, inserting: Boolean = false) {
    CitationForm(state, controller, paragraph, onCancel, onInsert, onAddKey,
        cslNote = if (cslSelected) citationPreviewNote() else null, restriction = restriction, inserting = inserting)
}
