package org.blaykie.nativeapp.render

import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.docx.DocxProject

@Composable
internal fun MobileDocumentScreen(project: DocxProject, onBack: () -> Unit) {
    val scroll = rememberLazyListState()
    var model by remember(project) { mutableStateOf<DocumentRenderModel?>(null) }
    var failed by remember(project) { mutableStateOf(false) }
    LaunchedEffect(project) {
        try {
            val input = project.renderInput()
            model = withContext(Dispatchers.Default) { input.read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val fonts = remember { mutableMapOf<String, FontFamily?>() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to document")
            }
            Text("Mobile view", style = MaterialTheme.typography.titleLarge)
        }
        val blocks = model?.blocks
        when {
            failed -> Text("Could not load the mobile view for this document.", Modifier.padding(16.dp))
            blocks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            blocks.isEmpty() -> Text("No readable document content.", Modifier.padding(16.dp))
            else -> LazyColumn(state = scroll, contentPadding = PaddingValues(16.dp)) {
                items(blocks) { block ->
                    when (block) {
                        is RenderParagraph -> MobileParagraph(block, fonts)
                        is RenderPlaceholder -> Text(block.label, Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileParagraph(paragraph: RenderParagraph, fonts: MutableMap<String, FontFamily?>) {
    val body = MaterialTheme.typography.bodyLarge
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val heading = paragraph.headingLevel
    val defaultSize = if (heading == null) body.fontSize.value else (28f - (heading - 1) * 2).coerceAtLeast(18f)
    val text = remember(paragraph, body, background, foreground) {
        buildAnnotatedString {
            paragraph.list?.let { append(it.label); append("\u00a0\u00a0") }
            // Keep same-format runs together so shaping can cross Word's run boundaries.
            var previous: RunFormat? = null
            val buffer = StringBuilder()
            fun flush() {
                val format = previous ?: return
                val family = format.font?.let { name ->
                    if (fonts.containsKey(name)) fonts[name] else availableFont(name).also { fonts[name] = it }
                } ?: body.fontFamily
                val color = format.rgb?.let { Color(it or 0xff000000.toInt()) } ?: foreground
                val contrast = (maxOf(color.luminance(), background.luminance()) + .05f) /
                    (minOf(color.luminance(), background.luminance()) + .05f)
                withStyle(SpanStyle(
                    fontFamily = family, fontSize = (format.sizePoints?.coerceIn(6f, 100f) ?: defaultSize).sp,
                    fontWeight = if (format.bold ?: (heading != null)) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (format.italic == true) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (format.underline == true) TextDecoration.Underline else TextDecoration.None,
                    color = if (contrast >= 3f) color else foreground,
                )) { append(buffer.toString()) }
                buffer.setLength(0)
            }
            for (run in paragraph.runs) {
                if (run.format != previous) { flush(); previous = run.format }
                buffer.append(run.text)
            }
            flush()
        }
    }
    val left = paragraph.leftTwips.coerceIn(0, 2400) / 20f
    val hanging = paragraph.hangingTwips.coerceIn(0, 1440) / 20f
    val listIndent = paragraph.list?.let { 16f + it.level * 10f } ?: 0f
    val continuation = if (left == 0f) listIndent else left
    val first = if (paragraph.list != null && hanging == 0f) (continuation - 16f).coerceAtLeast(0f)
        else (continuation - hanging + paragraph.firstLineTwips.coerceIn(0, 1440) / 20f).coerceAtLeast(0f)
    val maxSize = paragraph.runs.maxOfOrNull { it.format.sizePoints?.coerceIn(6f, 100f) ?: defaultSize } ?: defaultSize
    val line = paragraph.lineSpacing?.takeIf { it > 0 }?.let {
        if (paragraph.lineRule == "exact" || paragraph.lineRule == "atLeast") (it / 20f).coerceIn(maxSize, 150f)
        else (maxSize * 1.2f * it / 240f).coerceIn(maxSize, 150f)
    } ?: (maxSize * 1.2f)
    Text(text, modifier = Modifier.fillMaxWidth().padding(
        top = (paragraph.beforeTwips.coerceIn(0, 1440) / 20f).dp,
        bottom = (paragraph.afterTwips.coerceIn(0, 1440) / 20f).dp,
    ), color = foreground, style = body.copy(fontSize = defaultSize.sp, lineHeight = line.sp,
        textIndent = TextIndent(firstLine = first.sp, restLine = continuation.sp),
        textAlign = when (paragraph.alignment) {
            RenderAlignment.Start -> TextAlign.Start; RenderAlignment.End -> TextAlign.End
            RenderAlignment.Left -> TextAlign.Left; RenderAlignment.Right -> TextAlign.Right
            RenderAlignment.Center -> TextAlign.Center; RenderAlignment.Justify -> TextAlign.Justify
        }))
}

private fun availableFont(name: String): FontFamily? = when (name.lowercase()) {
    "sans-serif" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> runCatching {
        val typeface = Typeface.create(name, Typeface.NORMAL)
        if (typeface == Typeface.DEFAULT) null else FontFamily(typeface)
    }.getOrNull()
}
