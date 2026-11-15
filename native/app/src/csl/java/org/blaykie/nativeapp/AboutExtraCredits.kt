package org.blaykie.nativeapp

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal fun AboutExtraCredits() {
    val uri = LocalUriHandler.current
    Text("Citation styles", style = MaterialTheme.typography.titleMedium)
    Text("English locale data by the Citation Style Language project and its contributors; CC BY-SA 3.0.", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { runCatching { uri.openUri("https://citationstyles.org/") } }) { Text("https://citationstyles.org/", style = MaterialTheme.typography.bodyMedium) }
    TextButton(onClick = { runCatching { uri.openUri("https://creativecommons.org/licenses/by-sa/3.0/") } }) { Text("English locale: CC BY-SA 3.0", style = MaterialTheme.typography.bodyMedium) }
    Text("citeproc-js by Frank Bennett, used under its AGPL-3.0-or-later option.", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { runCatching { uri.openUri("https://github.com/Juris-M/citeproc-js") } }) { Text("https://github.com/Juris-M/citeproc-js", style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
}
