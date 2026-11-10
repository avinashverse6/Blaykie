package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

data class AboutLibrary(val name: String, val license: String, val url: String)
internal object AboutInfo {
    const val appName = "Blaykie"
    const val license = "Licensed under AGPL-3.0-or-later"
    const val source = "https://github.com/avinashverse6/Blaykie"
    const val apacheLicense = "https://www.apache.org/licenses/LICENSE-2.0"
    const val licenseAsset = "licenses/AGPL-3.0"
    val libraries = listOf(
        AboutLibrary("AndroidX Activity Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX Compose UI", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX Compose Material 3", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX DataStore Preferences", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
        AboutLibrary("kotlinx.serialization JSON", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        AboutLibrary("Material Design icons", "Apache-2.0", "https://github.com/google/material-design-icons"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val licenseText = remember { runCatching { context.assets.open(AboutInfo.licenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "License text could not be loaded." } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("About") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AboutInfo.appName, style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(AboutInfo.license, style = MaterialTheme.typography.bodyLarge)
            Text("The app sends network requests only to api.zotero.org. Links open in your browser.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { runCatching { uri.openUri(AboutInfo.source) } }) { Text("Source: ${AboutInfo.source}") }
            HorizontalDivider()
            Text("Third-party libraries and artwork", style = MaterialTheme.typography.titleMedium)
            AboutInfo.libraries.forEach { library ->
                Text("${library.name} — ${library.license}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { runCatching { uri.openUri(library.url) } }) { Text(library.url, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Other transitive components and their notices require a distribution-specific audit.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri(AboutInfo.apacheLicense) } }) { Text("Apache-2.0 license (upstream)") }
            HorizontalDivider()
            Text("GNU Affero General Public License", style = MaterialTheme.typography.titleMedium)
            Text(licenseText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
