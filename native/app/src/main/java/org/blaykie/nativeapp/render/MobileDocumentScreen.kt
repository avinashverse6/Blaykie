package org.blaykie.nativeapp.render

import android.graphics.Typeface
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.blaykie.nativeapp.BibliographyStatus
import org.blaykie.nativeapp.BibliographyViewState
import org.blaykie.nativeapp.bibliography
import org.blaykie.nativeapp.docx.DocxProject

@Composable
internal fun MobileDocumentScreen(project: DocxProject, state: MobileDocumentState, footer: BibliographyViewState?,
                                  onParagraph: (Int) -> Unit, onCitation: (Int, Int) -> Unit) {
    var failed by remember(project, state.revision) { mutableStateOf(false) }
    val hideBibliography = footer?.content?.end?.bibliography?.isNotEmpty() == true
    LaunchedEffect(project, state.revision, hideBibliography) {
        try {
            state.update(project, hideBibliography)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val fonts = remember { mutableMapOf<String, FontFamily?>() }
    val tableScroll = remember(state) { mutableMapOf<Int, ScrollState>() }
    val content = state.page
    when {
        failed -> Text("Could not load the mobile view for this document.", Modifier.padding(16.dp))
        content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(state = state.scroll, contentPadding = PaddingValues(16.dp)) {
            if (content.items.isEmpty()) item { Text("No readable document content.", Modifier.padding(16.dp)) }
            items(content.items) { item ->
                when (item) {
                    is MobilePageItem.Block -> when (val block = item.content) {
                        is RenderParagraph -> MobileParagraph(block, fonts,
                            onParagraph = { onParagraph(item.blockIndex) }, onCitation = { onCitation(item.blockIndex, it) })
                        is RenderImage -> MobileImage(content.source, block)
                        is RenderTable -> Unit // Table bands are emitted separately in pageItems().
                    }
                    is MobilePageItem.TableRows -> MobileTableBand(content.source, item.widths, item.band,
                        tableScroll.getOrPut(item.blockIndex) { ScrollState(0) }, fonts)
                }
            }
            if (footer != null) {
                val result = footer.content
                if (result == null) item { BibliographyStatus(footer, Modifier.padding(vertical = 12.dp)) }
                else {
                    if (result.end.bibliography.isEmpty() && result.missingCitations.isEmpty()) item { Text("No citations yet.") }
                    bibliography(result.end.bibliography)
                    if (footer.lookupAttempted) item { androidx.compose.material3.TextButton(onClick = footer.retry) { Text("Retry") } }
                    result.missingNote?.let { note -> item { Text(note, style = MaterialTheme.typography.titleSmall) } }
                    items(result.missingCitations) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
internal fun MobileParagraph(paragraph: RenderParagraph, fonts: MutableMap<String, FontFamily?>,
                             onParagraph: (() -> Unit)? = null, onCitation: ((Int) -> Unit)? = null) {
    val body = MaterialTheme.typography.bodyLarge
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val heading = paragraph.headingLevel
    val defaultSize = if (heading == null) body.fontSize.value else (28f - (heading - 1) * 2).coerceAtLeast(18f)
    val citationBackground = MaterialTheme.colorScheme.primaryContainer
    val citationForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val liveCitationHandler by rememberUpdatedState(onCitation)
    val text = remember(paragraph, body, background, foreground, citationBackground, citationForeground, onCitation != null) {
        buildAnnotatedString {
            paragraph.list?.let { append(it.label); append("\u00a0\u00a0") }
            // Keep same-format runs together so shaping can cross Word's run boundaries.
            var previous: RunFormat? = null
            var citation: Int? = null
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
                    color = if (citation != null) citationForeground else if (contrast >= 3f) color else foreground,
                    background = if (citation != null) citationBackground else Color.Unspecified,
                )) {
                    val number = citation
                    if (number != null && onCitation != null) {
                        withLink(LinkAnnotation.Clickable("citation-$number", TextLinkStyles(),
                            LinkInteractionListener { liveCitationHandler?.invoke(number) })) { append(buffer.toString()) }
                    } else append(buffer.toString())
                }
                buffer.setLength(0)
            }
            for (run in paragraph.runs) {
                if (run.format != previous || run.citationNumber != citation) {
                    flush(); previous = run.format; citation = run.citationNumber
                }
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
    val interaction = if (onParagraph == null) Modifier else Modifier.clickable(onClick = onParagraph)
    Text(text, modifier = Modifier.fillMaxWidth().then(interaction).padding(
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
