package org.blaykie.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object Space {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val extraLarge = 32.dp
}
internal object AppShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(20.dp)
}

@Composable
internal fun AppPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = AppShapes.small, content = content)
}

@Composable
internal fun AppSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = AppShapes.small, content = content)
}

@Composable
internal fun AppTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = AppShapes.small, content = content)
}

@Composable
internal fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier, shape = AppShapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), content = content)
}

@Composable
internal fun AppTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
        modifier = modifier.heightIn(min = 56.dp), shape = AppShapes.small, singleLine = true)
}

@Composable
internal fun selectedOptionModifier(selected: Boolean): Modifier = Modifier.clip(AppShapes.small)
    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)

@Composable
internal fun AppEmptyState(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun AppErrorState(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier = modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
