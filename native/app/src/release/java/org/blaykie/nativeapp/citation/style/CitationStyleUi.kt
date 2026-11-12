package org.blaykie.nativeapp.citation.style

import android.content.Context
import androidx.compose.runtime.Composable
import org.blaykie.nativeapp.session.Session

@Composable internal fun CitationStyleSession(context: Context, session: Session?, active: Boolean, loaded: Boolean, onStyleChange: (String?) -> Boolean) { }
@Composable internal fun CitationStyleRow(context: Context, session: Session?, onStyleChange: (String?) -> Boolean) { }
