package org.blaykie.nativeapp.export

import android.content.Context
import org.blaykie.nativeapp.session.Session

internal fun beginExport(context: Context, controller: ExportController, session: Session) = controller.begin(session)
