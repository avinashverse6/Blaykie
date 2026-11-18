package org.blaykie.nativeapp.citation.style

import androidx.compose.runtime.Composable
import org.blaykie.nativeapp.Settings
import org.blaykie.nativeapp.SettingsStore

internal fun settingsSectionVisible(enabled: Boolean): Boolean = false

@Composable
internal fun SettingsExtraSection(settings: Settings, store: SettingsStore) = Unit
