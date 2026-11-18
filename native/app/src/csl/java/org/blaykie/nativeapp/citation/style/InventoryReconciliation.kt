package org.blaykie.nativeapp.citation.style

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.blaykie.nativeapp.SettingsStore
import org.blaykie.nativeapp.csl.LocaleImportStore
import org.blaykie.nativeapp.csl.StyleRegistry
import org.blaykie.nativeapp.csl.styleChoices

private val mutableInventoryRevision = MutableStateFlow(0L)
internal val inventoryRevision = mutableInventoryRevision.asStateFlow()

/** Called on the IO dispatcher, both immediately before and after a successful removal. */
internal fun loadSelectionInventory(context: Context): SelectionInventory = SelectionInventory(
    styleChoices(StyleRegistry.load({ context.assets.open(it) }, File(context.filesDir, "styles"))),
    LocaleImportStore(File(context.filesDir, "locales")).list(),
)

/** Record the fallback atomically before notifying any open session. */
internal suspend fun reconcileInventoryRemoval(context: Context, before: SelectionInventory, after: SelectionInventory): Boolean {
    var changed = false
    try {
        SettingsStore(context).updateOptionalSelection { stored ->
            reconcileRemoval(stored, before, after).also { changed = it.changed }.settings
        }
    } finally {
        // Even if writing the message fails, make the active session re-check availability.
        if (changed) mutableInventoryRevision.value += 1
    }
    return changed
}
