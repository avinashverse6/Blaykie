package org.blaykie.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaykie.nativeapp.session.EditOperation

internal data class DocumentMarker(val edited: Boolean = false, val citations: Int = 0)
internal fun documentMarkers(edits: List<EditOperation>): Map<Int, DocumentMarker> =
    edits.groupBy { it.block }.mapValues { (_, operations) ->
        DocumentMarker(operations.any { it.type == "edit" }, operations.count { it.type == "citation" })
    }
internal fun documentSummary(citations: Int, edits: Int): String = "$citations citations added · $edits edits"

@Composable
internal fun DocumentTopBar(filename: String, citations: Int, edits: Int, onClose: () -> Unit,
                            onUndo: () -> Unit, onExport: () -> Unit, extraSubtitle: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Close")
        }
        Column(Modifier.weight(1f)) {
            Text(filename, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(documentSummary(citations, edits), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            extraSubtitle()
        }
        IconButton(onClick = onUndo, enabled = edits > 0, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo")
        }
        Button(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Export", maxLines = 1) }
    }
}

@Composable
internal fun DocumentTonalBanner(content: @Composable RowScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = AppShapes.small, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun DocumentChip(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun DocumentBlock(kind: String, text: String, marker: DocumentMarker?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val heading = kind.startsWith("heading")
    val dimmed = kind == "table" || kind == "other"
    val border = BorderStroke(if (focused) 2.dp else 1.dp,
        if (focused) MaterialTheme.colorScheme.primary else if (heading) Color.Transparent else MaterialTheme.colorScheme.outline)
    val color = if (heading) Color.Transparent else if (dimmed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val contentColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val headingStyle = when (kind.removePrefix("heading").toIntOrNull()) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }.copy(fontWeight = FontWeight.Bold)
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = AppShapes.medium, border = border, color = color, contentColor = contentColor,
        interactionSource = interaction) {
        Column(Modifier.padding(if (heading) PaddingValues(horizontal = 4.dp, vertical = 12.dp) else PaddingValues(16.dp))) {
            if (kind == "table") Text("Table", style = MaterialTheme.typography.labelSmall)
            Text(text, style = if (heading) headingStyle else MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp))
            if (marker?.edited == true || (marker?.citations ?: 0) > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (marker?.edited == true) DocumentChip("Edited")
                    val count = marker?.citations ?: 0
                    if (count > 0) DocumentChip("$count citations")
                }
            }
        }
    }
}
