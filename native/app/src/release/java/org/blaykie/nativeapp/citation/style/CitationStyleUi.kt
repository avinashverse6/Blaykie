package org.blaykie.nativeapp.citation.style

import android.content.Context
import androidx.compose.runtime.Composable
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.citation.SearchState
import org.blaykie.nativeapp.docx.CitationItem

@Composable internal fun CitationStyleSession(context: Context, session: Session?, active: Boolean, loaded: Boolean, onStyleChange: (String?) -> Boolean) { }
@Composable internal fun CitationStyleRow(context: Context, session: Session?, onStyleChange: (String?) -> Boolean) { }

internal fun citationUsesCsl(session: Session?): Boolean = false
internal fun citationOptionRestriction(context: Context, session: Session?, search: SearchState): String? = null
internal suspend fun renderCitationPreview(context: Context, session: Session, search: SearchState, citationContext: List<CitationItem>): String? = null

internal fun exportCslStyle(context: Context, session: Session): org.blaykie.nativeapp.export.ExportCslStyle? = null
