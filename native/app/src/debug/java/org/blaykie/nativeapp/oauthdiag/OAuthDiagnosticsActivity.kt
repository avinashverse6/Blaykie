package org.blaykie.nativeapp.oauthdiag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.BuildConfig

/** Debug-only screen: tries request-token variants against Zotero and shows safe results. */
class OAuthDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { OAuthDiagnosticsScreen() }
            }
        }
    }
}

@Composable
private fun OAuthDiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("OAuth diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Debug only. This asks Zotero for a request token in seven different ways and shows " +
            "what came back. It never continues to authorize, and no secrets, signatures, nonces " +
            "or token values are shown.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Client key present: ${BuildConfig.ZOTERO_OAUTH_CLIENT_KEY.isNotBlank()}",
            style = MaterialTheme.typography.bodySmall)
        Text("Client secret present: ${BuildConfig.ZOTERO_OAUTH_CLIENT_SECRET.isNotBlank()}",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (running) return@Button
            running = true
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    OAuthDiagnosticsRunner(BuildConfig.ZOTERO_OAUTH_CLIENT_KEY,
                        BuildConfig.ZOTERO_OAUTH_CLIENT_SECRET).run()
                }
                running = false
            }
        }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (running) CircularProgressIndicator(Modifier.height(20.dp))
            else Text("Run")
        }
        if (report.isNotEmpty()) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Blaykie OAuth diagnostics", report))
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy results") }
            Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
