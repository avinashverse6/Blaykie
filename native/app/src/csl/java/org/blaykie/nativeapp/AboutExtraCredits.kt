package org.blaykie.nativeapp

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutExtraCredits() {
    val uri = LocalUriHandler.current
    SettingsSectionTitle("Citation styles")
    Text("English locale data by the Citation Style Language project and its contributors; CC BY-SA 3.0.", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { runCatching { uri.openUri("https://citationstyles.org/") } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("https://citationstyles.org/", style = MaterialTheme.typography.bodyMedium) }
    TextButton(onClick = { runCatching { uri.openUri("https://creativecommons.org/licenses/by-sa/3.0/") } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("English locale: CC BY-SA 3.0", style = MaterialTheme.typography.bodyMedium) }
    Text("citeproc-js by Frank Bennett, used under its AGPL-3.0-or-later option.", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { runCatching { uri.openUri("https://github.com/Juris-M/citeproc-js") } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("https://github.com/Juris-M/citeproc-js", style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
}
