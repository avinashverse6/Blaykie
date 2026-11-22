package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class AboutLibrary(val name: String, val license: String, val url: String)
internal object AboutInfo {
    const val appName = "Blaykie"
    const val license = "Licensed under AGPL-3.0-or-later"
    const val source = "https://github.com/avinashverse6/Blaykie"
    const val privacy = "Your Zotero key is stored encrypted on this phone. Blaykie has no analytics and contacts only api.zotero.org."
    const val fontCredit = "Lora — SIL Open Font License 1.1"
    const val fontSource = "https://fonts.google.com/specimen/Lora"
    const val timesFontCredit = "Tinos — SIL Open Font License 1.1"
    const val timesFontSource = "https://github.com/googlefonts/tinos"
    const val apacheLicense = "https://www.apache.org/licenses/LICENSE-2.0"
    const val licenseAsset = "licenses/AGPL-3.0"
    const val apacheLicenseAsset = "licenses/Apache-2.0"
    val libraries = listOf(
        AboutLibrary("AndroidX Activity Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX Compose UI", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX Compose Material 3", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("AndroidX DataStore Preferences", "Apache-2.0", "https://github.com/androidx/androidx"),
        AboutLibrary("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
        AboutLibrary("kotlinx.serialization JSON", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        AboutLibrary("Kotlin standard library", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
        AboutLibrary("Kotlin coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        AboutLibrary("Okio", "Apache-2.0", "https://github.com/square/okio"),
        AboutLibrary("Material Design settings, back and undo vector icons", "Apache-2.0", "https://github.com/google/material-design-icons"),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val licenseRequester = remember { BringIntoViewRequester() }
    var showApache by rememberSaveable { mutableStateOf(false) }
    var showFontLicense by rememberSaveable { mutableStateOf(false) }
    val apacheText = remember { runCatching { context.assets.open(AboutInfo.apacheLicenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Apache-2.0 license text could not be loaded." } }
    val fontLicenseText = remember { runCatching { context.resources.openRawResource(R.raw.lora_ofl).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Font license text could not be loaded." } }
    val licenseText = remember { runCatching { context.assets.open(AboutInfo.licenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "License text could not be loaded." } }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("About", onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(size = 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(AboutInfo.appName, style = MaterialTheme.typography.headlineSmall)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsSectionTitle("Licence")
            Text(AboutInfo.license, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { scope.launch { licenseRequester.bringIntoView() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read full licence text") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsSectionTitle("Source")
            TextButton(onClick = { runCatching { uri.openUri(AboutInfo.source) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Source: ${AboutInfo.source}") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsSectionTitle("Privacy")
            Text(AboutInfo.privacy, style = MaterialTheme.typography.bodyMedium)
            Text("The app sends network requests only to api.zotero.org. Links open in your browser.", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            AboutExtraCredits()
            SettingsSectionTitle("Fonts")
            Text(AboutInfo.fontCredit, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { runCatching { uri.openUri(AboutInfo.fontSource) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(AboutInfo.fontSource, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { showFontLicense = !showFontLicense }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showFontLicense) "Hide font license text" else "Read font license text") }
            if (showFontLicense) Text(fontLicenseText, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsSectionTitle("Third-party libraries and artwork")
            AboutInfo.libraries.forEach { library ->
                Text("${library.name} — ${library.license}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { runCatching { uri.openUri(library.url) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(library.url, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Other transitive components and their notices require a distribution-specific audit.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showApache = !showApache }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showApache) "Hide Apache-2.0 license text" else "Read Apache-2.0 license text") }
            if (showApache) Text(apacheText, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri(AboutInfo.apacheLicense) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apache-2.0 license (upstream)") }
            HorizontalDivider()
            Text("GNU Affero General Public License", modifier = Modifier.bringIntoViewRequester(licenseRequester), style = MaterialTheme.typography.titleMedium)
            Text(licenseText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
