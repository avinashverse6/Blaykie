package org.blaykie.nativeapp

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutExtraCredits() {
    val uri = LocalUriHandler.current
    SettingsSectionTitle("Citation styles")
    SettingsCard {
        SelectionContainer { SettingsInfoRow("English locale data by the Citation Style Language project and its contributors; CC BY-SA 3.0.") }
        SettingsListRow("https://citationstyles.org/") { runCatching { uri.openUri("https://citationstyles.org/") } }
        SettingsListRow("English locale: CC BY-SA 3.0") { runCatching { uri.openUri("https://creativecommons.org/licenses/by-sa/3.0/") } }
        SettingsDivider()
        SelectionContainer { SettingsInfoRow("citeproc-js by Frank Bennett, used under its AGPL-3.0-or-later option.") }
        SettingsListRow("https://github.com/Juris-M/citeproc-js") { runCatching { uri.openUri("https://github.com/Juris-M/citeproc-js") } }
    }
    if (BuildConfig.DEBUG) {
        val context = LocalContext.current
        SettingsCard {
            SettingsListRow("OAuth diagnostics (debug)") {
                try {
                    context.startActivity(Intent().setClassName(context,
                        "org.blaykie.nativeapp.oauthdiag.OAuthDiagnosticsActivity"))
                } catch (_: Exception) { }
            }
        }
    }
}
