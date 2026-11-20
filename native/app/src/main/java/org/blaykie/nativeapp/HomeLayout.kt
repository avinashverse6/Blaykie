package org.blaykie.nativeapp

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
internal fun BrandPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = R.drawable.ic_document_plus) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = CircleShape) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Space.small))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun HomeHeader(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrandLogo()
        Spacer(Modifier.width(Space.medium))
        Text("Blaykie", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        }
    }
}

@Composable
internal fun HomeLayout(
    textSize: TextSize, message: String, extraStep: HomeExtraStep? = null,
    onOpen: () -> Unit, onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.large, vertical = Space.medium)) {
        HomeHeader(onSettings)
        Spacer(Modifier.height(40.dp))
        BrandChip("APA 7 · ZOTERO")
        Spacer(Modifier.height(Space.medium))
        Text("Cite smarter. Write with confidence.",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(Space.medium))
        Text("Insert APA 7 citations from your Zotero library into your own Word file.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.large))
        BrandPrimaryButton("Open a Word document", onOpen, Modifier.fillMaxWidth())
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(Space.medium))
            AppErrorState(message)
        }
        Spacer(Modifier.height(Space.extraLarge))
        Text("How Blaykie works", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Space.medium))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val steps = homeSteps(extraStep, includePreview = extraStep == null)
            val vertical = verticalHomeSteps(maxWidth.value, textSize, LocalDensity.current.fontScale) ||
                (steps.size > 3 && maxWidth.value < 480f)
            if (vertical) VerticalSteps(steps) else HorizontalSteps(steps)
        }
        Spacer(Modifier.height(Space.extraLarge))
    }
}

internal data class HomeExtraStep(val label: String, val icon: Int)
internal data class HomeStep(val number: String, val label: String, val icon: Int?)
internal fun homeSteps(extra: HomeExtraStep?, includePreview: Boolean = false): List<HomeStep> = buildList {
    add(HomeStep("01", "Open your document", R.drawable.ic_document_outline))
    if (extra != null) add(HomeStep("02", extra.label, extra.icon))
    add(HomeStep(if (extra == null) "02" else "03", "Insert Zotero citations", null))
    if (includePreview && extra == null) add(HomeStep("03", "Preview your bibliography", R.drawable.ic_document_outline))
    add(HomeStep(if (extra == null && !includePreview) "03" else "04", "Export a new doc with bibliography", R.drawable.ic_export_arrow))
}

@Composable
internal fun HomeIconBadge(icon: Int?) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        else Text("Z", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HorizontalSteps(steps: List<HomeStep>) {
    Row(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HomeIconBadge(step.icon)
                    if (index < steps.lastIndex) HorizontalDivider(Modifier.weight(1f).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(Space.small))
                Text(step.number, style = MaterialTheme.typography.titleMedium)
                Text(step.label, modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VerticalSteps(steps: List<HomeStep>) {
    Column {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                HomeIconBadge(step.icon)
                Spacer(Modifier.width(Space.medium))
                Column(Modifier.weight(1f)) {
                    Text(step.number, style = MaterialTheme.typography.titleMedium)
                    Text(step.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (index < steps.lastIndex) VerticalDivider(Modifier.padding(start = 19.dp).height(16.dp), color = MaterialTheme.colorScheme.outline)
        }
    }
}
