package org.blaykie.nativeapp.account

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.blaykie.nativeapp.AppErrorState
import org.blaykie.nativeapp.BrandLogo
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.Space

internal fun canConnectKey(key: String, checking: Boolean, warning: Boolean): Boolean =
    key.isNotBlank() && !checking && !warning

internal fun nextKeyVisibility(visible: Boolean): Boolean = !visible

@Composable
internal fun AccountGate(activity: Activity, controller: AccountController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    val holder = rememberSaveableStateHolder()
    DisposableEffect(activity, state.route) {
        if (state.route == AccountRoute.KEY || state.route == AccountRoute.FIRST_RUN) {
            val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        } else onDispose { }
    }
    val openBrowser = {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zotero.org/settings/keys/new"))) } catch (_: Exception) { }
    }
    when (state.route) {
        AccountRoute.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AccountRoute.APP -> holder.SaveableStateProvider("application") { content() }
        AccountRoute.FIRST_RUN -> {
            BackHandler { }
            KeyScreen(state, firstRun = true, onOpenBrowser = openBrowser, onSave = { controller.verify(it) },
                onCancel = { controller.openKey(true); controller.cancelKey() },
                onSkip = { controller.skip() }, onUseAnyway = { controller.useAnyway() })
        }
        AccountRoute.KEY -> {
            BackHandler { controller.cancelKey() }
            KeyScreen(state, firstRun = false, onOpenBrowser = openBrowser, onSave = { controller.verify(it) },
                onCancel = { controller.cancelKey() }, onSkip = {}, onUseAnyway = { controller.useAnyway() })
        }
    }
}

@Composable
private fun KeyScreen(state: AccountState, firstRun: Boolean, onOpenBrowser: () -> Unit,
                      onSave: (String) -> Unit, onCancel: () -> Unit, onSkip: () -> Unit, onUseAnyway: () -> Unit) {
    // Deliberately not saveable: the field is never placed in saved instance state.
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { key = "" } }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = Space.large, vertical = Space.large), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) {
                IconButton(onClick = { key = ""; onCancel() }, enabled = !state.checking, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
                Spacer(Modifier.width(Space.small))
            }
            BrandLogo(size = 40)
            Spacer(Modifier.width(Space.medium))
            Text("Blaykie", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(Space.medium))
        Text(if (firstRun) "Connect your Zotero library" else "Change your key", style = MaterialTheme.typography.headlineMedium)
        Text("Paste your Zotero API key to insert citations.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.small))
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Zotero API key") },
            enabled = !state.checking && !state.warning, singleLine = true, shape = RoundedCornerShape(16.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { visible = nextKeyVisibility(visible) }, enabled = !state.checking && !state.warning,
                    modifier = Modifier.semantics { contentDescription = if (visible) "Hide Zotero API key" else "Show Zotero API key" }) {
                    Text(if (visible) "Hide" else "Show")
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
        Button(onClick = { onSave(key) }, enabled = canConnectKey(key, state.checking, state.warning),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape) {
            if (state.checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.small))
            }
            Text("Connect")
        }
        Text("Stored encrypted on this phone", modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.message.isNotEmpty()) AppErrorState(state.message)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("or", Modifier.padding(horizontal = Space.medium), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }
        OutlinedButton(onClick = onOpenBrowser, enabled = !state.checking && !state.warning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape) { Text("Get a key on zotero.org") }
        if (firstRun) {
            TextButton(onClick = { key = ""; onSkip() }, enabled = !state.checking && !state.warning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Skip for now") }
            Text("You can add a key later in Settings → Account.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.warning) AlertDialog(
        onDismissRequest = { key = ""; onCancel() },
        title = { Text("This key has broader access") },
        text = { Text("This key can write to a library or access groups. For a read-only key, create a new key on zotero.org with access only to your personal library and no write access.") },
        confirmButton = { TextButton(onClick = { key = ""; onUseAnyway() }) { Text("Use anyway") } },
        dismissButton = { TextButton(onClick = { key = ""; onCancel() }) { Text("Cancel") } },
    )
}

@Composable
internal fun AccountSection(state: AccountState, onChange: () -> Unit, onRemove: () -> Unit) {
    Column {
        val account = state.account
        if (account == null) {
            Text("No key saved", modifier = Modifier.padding(vertical = 12.dp))
        } else {
            val p = account.permissions
            val level = when {
                p.personalWrite && p.groupAccess -> "Write access and group access"
                p.personalWrite -> "Write access"
                p.groupAccess -> "Group access"
                p.personalLibrary -> "Personal library: read-only"
                else -> "No personal-library access"
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("User ID: ${account.userId}", style = MaterialTheme.typography.bodyLarge)
                Text("Permissions: $level", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (account.lastFour.isEmpty()) "Key: ••••" else "Key: ••••${account.lastFour}", modifier = Modifier.padding(vertical = 12.dp))
        }
        TextButton(enabled = !state.checking, onClick = onChange, modifier = Modifier.fillMaxWidth()) { Text(if (account == null) "Add key" else "Change key") }
        if (account != null) TextButton(enabled = !state.checking, onClick = onRemove, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove key from this phone") }
        Text("To revoke the key itself, use zotero.org.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.checking) CircularProgressIndicator()
        if (state.message.isNotEmpty()) Text(state.message, color = MaterialTheme.colorScheme.onSurface)
    }
}
