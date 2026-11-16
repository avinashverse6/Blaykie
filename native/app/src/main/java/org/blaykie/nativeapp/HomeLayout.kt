package org.blaykie.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun verticalHomeSteps(availableWidthDp: Float, textSize: TextSize, fontScale: Float): Boolean =
    availableWidthDp < 360f || textSize == TextSize.Large || fontScale >= 1.15f

@Composable
internal fun BrandChip(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp))
    }
}

@Composable
internal fun BrandLogo(size: Int = 48) {
    Image(painterResource(R.drawable.home_logo), contentDescription = null,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)))
}

@Composable
internal fun BrandPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = CircleShape) {
        Icon(painterResource(R.drawable.ic_document_plus), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Space.small))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun HomeLayout(
    textSize: TextSize, filename: String?, editCount: Int, message: String,
    onOpen: () -> Unit, onContinue: () -> Unit, onEnd: () -> Unit, onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo()
            Spacer(Modifier.width(Space.medium))
            Text("Blaykie", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(40.dp))
        BrandChip("APA 7 · ZOTERO")
        Spacer(Modifier.height(Space.medium))
        Text("Cite smarter. Write with confidence.",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(Space.medium))
        Text("Insert APA 7 citations from your Zotero library into your own Word file.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.large))
        BrandPrimaryButton("Open a Word document", onOpen, Modifier.fillMaxWidth())
        if (filename != null) {
            Spacer(Modifier.height(Space.extraLarge))
            Text("In progress", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.medium))
            Card(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(Space.medium), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(AppShapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_document_outline), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(Space.medium))
                    Column(Modifier.weight(1f)) {
                        Text("Continue working", style = MaterialTheme.typography.titleSmall)
                        Text(filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$editCount edits", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(Space.small))
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            AppTextButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) { Text("End session") }
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(Space.medium))
            AppErrorState(message)
        }
        Spacer(Modifier.height(Space.extraLarge))
        Text("How Blaykie works", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Space.medium))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val vertical = verticalHomeSteps(maxWidth.value, textSize, LocalDensity.current.fontScale)
            if (vertical) VerticalSteps() else HorizontalSteps()
        }
        Spacer(Modifier.height(Space.extraLarge))
    }
}

private data class HomeStep(val number: String, val label: String, val icon: Int?)
private val homeSteps = listOf(
    HomeStep("01", "Open your document", R.drawable.ic_document_outline),
    HomeStep("02", "Insert Zotero citations", null),
    HomeStep("03", "Export a new .docx", R.drawable.ic_export_arrow),
)

@Composable
private fun StepBadge(step: HomeStep) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        if (step.icon != null) Icon(painterResource(step.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        else Text("Z", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HorizontalSteps() {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            homeSteps.forEachIndexed { index, step ->
                StepBadge(step)
                if (index < homeSteps.lastIndex) HorizontalDivider(Modifier.weight(1f).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(Space.small))
        Row(Modifier.fillMaxWidth()) {
            homeSteps.forEach { step ->
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(step.number, style = MaterialTheme.typography.titleMedium)
                    Text(step.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun VerticalSteps() {
    Column {
        homeSteps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(step)
                Spacer(Modifier.width(Space.medium))
                Column(Modifier.weight(1f)) {
                    Text(step.number, style = MaterialTheme.typography.titleMedium)
                    Text(step.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (index < homeSteps.lastIndex) VerticalDivider(Modifier.padding(start = 19.dp).height(16.dp), color = MaterialTheme.colorScheme.outline)
        }
    }
}
