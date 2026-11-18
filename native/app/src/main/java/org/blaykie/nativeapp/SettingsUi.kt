package org.blaykie.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
        }
    })
}

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun SettingsListRow(title: String, value: String = "", enabled: Boolean = true,
                             destructive: Boolean = false, onClick: () -> Unit) {
    val titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    val iconColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f))
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = if (enabled) iconColor else iconColor.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp))
    }
}
