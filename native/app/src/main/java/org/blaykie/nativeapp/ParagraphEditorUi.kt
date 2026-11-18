package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ParagraphEditorTopBar(block: Int?, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Paragraph ${block ?: ""}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
    }
}

@Composable
internal fun ParagraphEditorActions(onInsert: () -> Unit, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onInsert, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(painterResource(R.drawable.ic_insert_citation), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Insert citation", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
    }
}
