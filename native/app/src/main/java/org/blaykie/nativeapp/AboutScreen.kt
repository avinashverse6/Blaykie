package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontWeight
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
    const val jakartaFontCredit = "Plus Jakarta Sans — SIL Open Font License 1.1"
    const val jakartaFontSource = "https://github.com/tokotype/PlusJakartaSans"
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
    var showTimesFontLicense by rememberSaveable { mutableStateOf(false) }
    var showJakartaFontLicense by rememberSaveable { mutableStateOf(false) }
    val apacheText = remember { runCatching { context.assets.open(AboutInfo.apacheLicenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Apache-2.0 license text could not be loaded." } }
    val fontLicenseText = remember { runCatching { context.resources.openRawResource(R.raw.lora_ofl).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Font license text could not be loaded." } }
    val jakartaFontLicenseText = remember { runCatching { context.resources.openRawResource(R.raw.plus_jakarta_sans_ofl).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Font license text could not be loaded." } }
    val timesFontLicenseText = remember { runCatching { context.resources.openRawResource(R.raw.tinos_ofl).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "Font license text could not be loaded." } }
    val licenseText = remember { runCatching { context.assets.open(AboutInfo.licenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrElse { "License text could not be loaded." } }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("About", onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BlaykieCatLogo(72.dp)
                Spacer(Modifier.height(12.dp))
                Text("Blaykie Citation Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSectionTitle("Licence")
            SettingsCard {
                SelectionContainer { SettingsInfoRow(AboutInfo.license) }
                SettingsDivider()
                SettingsListRow("Read full licence text") { scope.launch { licenseRequester.bringIntoView() } }
            }
            SettingsSectionTitle("Source")
            SettingsCard { SettingsListRow("Source: ${AboutInfo.source}") { runCatching { uri.openUri(AboutInfo.source) } } }
            SettingsSectionTitle("Privacy")
            SettingsCard {
                SelectionContainer { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AboutInfo.privacy, style = MaterialTheme.typography.bodyMedium)
                    Text("The app sends network requests only to api.zotero.org. Links open in your browser.", style = MaterialTheme.typography.bodyMedium)
                } }
            }
            AboutExtraCredits()
            SettingsSectionTitle("Fonts")
            SettingsCard {
                SelectionContainer { SettingsInfoRow(AboutInfo.jakartaFontCredit) }
                SettingsListRow(AboutInfo.jakartaFontSource) { runCatching { uri.openUri(AboutInfo.jakartaFontSource) } }
                SettingsListRow(if (showJakartaFontLicense) "Hide Plus Jakarta Sans license text" else "Read Plus Jakarta Sans license text") { showJakartaFontLicense = !showJakartaFontLicense }
                if (showJakartaFontLicense) SelectionContainer { Text(jakartaFontLicenseText, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                SettingsDivider()
                SelectionContainer { SettingsInfoRow(AboutInfo.fontCredit) }
                SettingsListRow(AboutInfo.fontSource) { runCatching { uri.openUri(AboutInfo.fontSource) } }
                SettingsListRow(if (showFontLicense) "Hide font license text" else "Read font license text") { showFontLicense = !showFontLicense }
                if (showFontLicense) SelectionContainer { Text(fontLicenseText, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                SettingsDivider()
                SelectionContainer { SettingsInfoRow(AboutInfo.timesFontCredit) }
                SettingsListRow(AboutInfo.timesFontSource) { runCatching { uri.openUri(AboutInfo.timesFontSource) } }
                SettingsListRow(if (showTimesFontLicense) "Hide Tinos license text" else "Read Tinos license text") { showTimesFontLicense = !showTimesFontLicense }
                if (showTimesFontLicense) SelectionContainer { Text(timesFontLicenseText, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            SettingsSectionTitle("Third-party libraries and artwork")
            SettingsCard {
                AboutInfo.libraries.forEach { library ->
                    SelectionContainer { SettingsInfoRow("${library.name} — ${library.license}") }
                    SettingsListRow(library.url) { runCatching { uri.openUri(library.url) } }
                    SettingsDivider()
                }
                SelectionContainer { Text("Other transitive components and their notices require a distribution-specific audit.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                SettingsListRow(if (showApache) "Hide Apache-2.0 license text" else "Read Apache-2.0 license text") { showApache = !showApache }
                if (showApache) SelectionContainer { Text(apacheText, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                SettingsListRow("Apache-2.0 license (upstream)") { runCatching { uri.openUri(AboutInfo.apacheLicense) } }
            }
            SettingsSectionTitle("GNU Affero General Public License")
            SettingsCard {
                SelectionContainer { Text(licenseText, modifier = Modifier.bringIntoViewRequester(licenseRequester).padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
