package org.blaykie.nativeapp.account

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.AppCard
import org.blaykie.nativeapp.AppErrorState
import org.blaykie.nativeapp.AppPrimaryButton
import org.blaykie.nativeapp.AppSecondaryButton
import org.blaykie.nativeapp.AppShapes
import org.blaykie.nativeapp.AppTextButton
import org.blaykie.nativeapp.Space
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountGate(activity: Activity, controller: AccountController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    val holder = rememberSaveableStateHolder()
    DisposableEffect(activity, state.route) {
        if (state.route == AccountRoute.KEY) {
            val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        } else onDispose { }
    }
    when (state.route) {
        AccountRoute.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        AccountRoute.APP -> holder.SaveableStateProvider("application") { content() }
        AccountRoute.FIRST_RUN -> {
            BackHandler { }
            FirstRunScreen(state, onOpenBrowser = {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zotero.org/settings/keys/new"))) } catch (_: Exception) { }
            }, onKey = { controller.openKey(true) }, onSkip = { controller.skip() })
        }
        AccountRoute.KEY -> {
            BackHandler { controller.cancelKey() }
            KeyScreen(state, onSave = { controller.verify(it) }, onCancel = { controller.cancelKey() }, onUseAnyway = { controller.useAnyway() })
        }
    }
}

@Composable
private fun FirstRunScreen(state: AccountState, onOpenBrowser: () -> Unit, onKey: () -> Unit, onSkip: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = Space.large, vertical = Space.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                Image(painterResource(R.drawable.home_logo), null, Modifier.size(96.dp).clip(AppShapes.medium))
                Text("Connect Zotero", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                        Text("1   Create a key for your personal library with read-only access.", style = MaterialTheme.typography.bodyMedium)
                        Text("2   Copy it and return to Blaykie.", style = MaterialTheme.typography.bodyMedium)
                        Text("3   Paste it here.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AppPrimaryButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) { Text("Create a key on zotero.org") }
                AppSecondaryButton(onClick = onKey, modifier = Modifier.fillMaxWidth()) { Text("I already have a key") }
                AppTextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
                Text("You can add a key later in Settings → Account.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (state.message.isNotEmpty()) AppErrorState(state.message)
            }
        }
    }
}

@Composable
private fun KeyScreen(state: AccountState, onSave: (String) -> Unit, onCancel: () -> Unit, onUseAnyway: () -> Unit) {
    // Deliberately not saveable: the field is never placed in saved instance state.
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = Space.large, vertical = Space.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                Image(painterResource(R.drawable.home_logo), null, Modifier.size(80.dp).clip(AppShapes.medium))
                Text("Zotero API key", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("Paste your key. Your user ID is obtained from Zotero.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") },
                    enabled = !state.checking && !state.warning, singleLine = true, shape = AppShapes.small,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    AppPrimaryButton(enabled = !state.checking && !state.warning, onClick = { onSave(key) }) { Text("Save") }
                    AppTextButton(enabled = !state.checking, onClick = { key = ""; onCancel() }) { Text("Cancel") }
                }
                if (state.checking) CircularProgressIndicator()
                if (state.message.isNotEmpty()) AppErrorState(state.message)
            }
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
