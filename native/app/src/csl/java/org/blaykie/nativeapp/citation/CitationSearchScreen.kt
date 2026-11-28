package org.blaykie.nativeapp.citation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.citation.style.citationPreviewNote
import org.blaykie.nativeapp.citation.style.renderCitationPreview
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.session.Session

private data class LivePreviewRequest(
    val state: SearchState, val session: Session?, val existing: List<CitationItem>, val enabled: Boolean,
)

@Composable
internal fun CitationSearchScreen(state: SearchState, controller: CitationSearchController, paragraph: String, onCancel: () -> Unit, onInsert: () -> Unit, onAddKey: () -> Unit, cslSelected: Boolean = false, restriction: String? = null, inserting: Boolean = false,
                                  session: Session? = null, existingCitations: List<CitationItem> = emptyList()) {
    val context = LocalContext.current
    val request = LivePreviewRequest(state, session, existingCitations,
        cslSelected && !state.savedOptions().needsApa && restriction == null && !inserting && state.selectedItems().isNotEmpty())
    val latest by rememberUpdatedState(request)
    var rendered by remember { mutableStateOf<Pair<LivePreviewRequest, String>?>(null) }
    LaunchedEffect(context) {
        // Conflate edits while rendering, and never cancel a render in the shared CSL host.
        snapshotFlow { latest }.conflate().collect { input ->
            if (!input.enabled || input.session == null) return@collect
            delay(250)
            if (latest != input) return@collect
            val result = withContext(NonCancellable) {
                renderCitationPreview(context, input.session, input.state, input.existing + input.state.selectedItems())
            }
            if (currentCoroutineContext().isActive && latest == input && result != null) rendered = input to result
        }
    }
    val preview = rendered?.takeIf { it.first == request }?.second
    CitationForm(state, controller, paragraph, onCancel, onInsert, onAddKey,
        cslNote = if (cslSelected) citationPreviewNote() else null, cslPreview = preview,
        restriction = restriction, inserting = inserting)
}
