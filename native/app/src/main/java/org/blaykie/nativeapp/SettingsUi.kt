package org.blaykie.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title, fontWeight = FontWeight.ExtraBold) }, navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
        }
    })
}

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Column(content = content) }
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)

private val settingsIcons = (0..5).map { kind ->
    ImageVector.Builder("Settings $kind", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (kind) {
                0 -> { moveTo(12f, 11f); curveTo(15f, 11f, 16f, 9f, 16f, 7f); curveTo(16f, 4f, 14f, 3f, 12f, 3f); curveTo(9f, 3f, 8f, 5f, 8f, 7f); curveTo(8f, 9f, 9f, 11f, 12f, 11f); moveTo(4f, 21f); curveTo(4f, 16f, 7f, 14f, 12f, 14f); curveTo(17f, 14f, 20f, 16f, 20f, 21f) }
                1 -> { moveTo(12f, 3f); curveTo(7f, 3f, 4f, 7f, 4f, 12f); curveTo(4f, 17f, 8f, 21f, 13f, 21f); curveTo(11f, 17f, 11f, 7f, 15f, 4f); lineTo(12f, 3f); close() }
                2 -> { moveTo(4f, 19f); lineTo(10f, 5f); lineTo(16f, 19f); moveTo(6f, 15f); lineTo(14f, 15f); moveTo(17f, 10f); lineTo(21f, 10f); moveTo(19f, 8f); lineTo(19f, 19f) }
                3 -> { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close(); moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(13f, 17f) }
                4 -> { moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f); curveTo(17f, 21f, 21f, 17f, 21f, 12f); curveTo(21f, 7f, 17f, 3f, 12f, 3f); moveTo(12f, 10f); lineTo(12f, 17f); moveTo(12f, 7f); lineTo(12f, 7.5f) }
                else -> { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close(); moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 14f); lineTo(16f, 14f) }
            }
        }
    }.build()
}
private fun settingsIconFor(title: String) = when {
    title.contains("key", true) || title.contains("Signed in", true) || title.contains("User ID", true) || title.contains("Permissions", true) || title.contains("Groups", true) -> 0
    title == "Theme" -> 1
    title == "Text size" -> 2
    title.contains("citation", true) || title.contains("language", true) || title.contains("style", true) -> 3
    title.contains("About", true) -> 4
    else -> 5
}

@Composable
private fun SettingsTile(title: String, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val index = settingsIconFor(title)
    val tint = if (destructive) colors.errorContainer else when (index % 3) { 0 -> colors.primaryContainer; 1 -> colors.secondaryContainer; else -> colors.tertiaryContainer }
    Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = tint) {
        Box(contentAlignment = Alignment.Center) { Icon(settingsIcons[index], null, Modifier.size(22.dp), tint = if (destructive) colors.onErrorContainer else colors.onSurface) }
    }
}

@Composable
internal fun SettingsListRow(title: String, value: String = "", enabled: Boolean = true,
                             destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (destructive) colors.error else colors.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsTile(title, destructive)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = if (enabled) titleColor else titleColor.copy(alpha = .38f))
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsInfoRow(title: String, value: String = "") {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsTile(title); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsTile(title); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp)); Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPickerSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = BottomSheetShape, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
            Text(title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).selectableGroup(), content = content)
        }
    }
}

@Composable
internal fun SettingsChoiceRow(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit,
                               supporting: @Composable ColumnScope.() -> Unit = {}, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); supporting() }
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

@Composable
internal fun SettingsAppearancePicker(which: String, settings: Settings, store: SettingsStore, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    SettingsPickerSheet(if (which == "theme") "Theme" else "Text size", dismiss) {
        if (which == "theme") ThemeMode.entries.forEach { mode ->
            SettingsChoiceRow(mode.name, settings.theme == mode, onClick = { scope.launch { store.setTheme(mode) }; dismiss() })
        } else TextSize.entries.forEach { size ->
            SettingsChoiceRow(size.name, settings.textSize == size, onClick = { scope.launch { store.setTextSize(size) }; dismiss() }, supporting = {
                Text("The quick brown fox", style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * (size.scale / settings.textSize.scale)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
        }
        TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
}
