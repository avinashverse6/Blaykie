package org.blaykie.nativeapp.render

import android.graphics.Typeface
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
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
import org.blaykie.nativeapp.R
import org.blaykie.nativeapp.bibliography
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.document.DocumentView

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MobileDocumentScreen(project: DocxProject, state: MobileDocumentState, footer: BibliographyViewState?,
                                  onViewChanged: () -> Unit, onParagraph: (Int) -> Unit, onCitation: (Int, Int) -> Unit) {
    var failed by remember(project, state.revision) { mutableStateOf(false) }
    val hideBibliography = remember(project) { project.hasBlaykieBibliography() } && when {
        footer == null || footer.error != null -> false
        footer.content == null -> state.hiddenBibliography
        else -> footer.content.end.bibliography.isNotEmpty()
    }
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
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        DocumentView.entries.forEachIndexed { index, view ->
            SegmentedButton(selected = state.view == view, onClick = {
                if (state.view != view) { state.switchView(view); onViewChanged() }
            }, shape = SegmentedButtonDefaults.itemShape(index, DocumentView.entries.size)) {
                Text(if (view == DocumentView.Mobile) "Mobile view" else "Print layout")
            }
        }
    }
    LaunchedEffect(content, state.view) {
        if (content != null && state.view == DocumentView.Mobile) state.restoreMobileFocus(content)
    }
    when {
        failed -> Text("Could not load the mobile view for this document.", Modifier.padding(16.dp))
        content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.view == DocumentView.Print -> PrintDocument(content, state, footer, fonts, tableScroll)
        else -> LazyColumn(state = state.scroll, contentPadding = PaddingValues(16.dp)) {
            if (content.items.isEmpty()) item { Text("No readable document content.", Modifier.padding(16.dp)) }
            itemsIndexed(content.items) { index, item ->
                DocumentBlock(content.source, item, fonts, tableScroll,
                    previousAfterTwips = ((content.items.getOrNull(index - 1) as? MobilePageItem.Block)
                        ?.content as? RenderParagraph)?.afterTwips ?: 0,
                    onParagraph = { onParagraph(item.blockIndex) }, onCitation = { onCitation(item.blockIndex, it) })
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
internal fun DocumentBlock(source: java.io.File, item: MobilePageItem, fonts: MutableMap<String, FontFamily?>,
                           tableScroll: MutableMap<Int, ScrollState>, previousAfterTwips: Int = 0,
                           onParagraph: (() -> Unit)? = null, onCitation: ((Int) -> Unit)? = null) {
    when (item) {
        is MobilePageItem.Block -> when (val block = item.content) {
            is RenderParagraph -> MobileParagraph(block, fonts, onParagraph, onCitation, previousAfterTwips)
            is RenderImage -> MobileImage(source, block)
            is RenderHorizontalRule -> MobileHorizontalRule(block)
            is RenderTable -> Unit
        }
        is MobilePageItem.TableRows -> MobileTableBand(source, item.widths, item.band,
            tableScroll.getOrPut(item.blockIndex) { ScrollState(0) }, fonts)
    }
}

@Composable
internal fun MobileParagraph(paragraph: RenderParagraph, fonts: MutableMap<String, FontFamily?>,
                             onParagraph: (() -> Unit)? = null, onCitation: ((Int) -> Unit)? = null,
                             previousAfterTwips: Int = 0) {
    val body = MaterialTheme.typography.bodyLarge
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val heading = paragraph.headingLevel
    val defaultSize = paragraph.defaultFontSize(body.fontSize.value)
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
                } ?: FontFamily.SansSerif
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
    val line = paragraph.lineHeightPoints(defaultSize)
    val interaction = if (onParagraph == null) Modifier else Modifier.clickable(onClick = onParagraph)
    val borders = paragraph.borders
    val edgeModifier = if (borders == RenderBorders()) Modifier else Modifier.drawBehind {
        fun edge(border: RenderBorder?, horizontal: Boolean, far: Boolean) {
            if (border == null) return
            val width = (border.widthEighthPoints / 8f).dp.toPx().coerceAtLeast(1f)
            val cross = if (horizontal) size.height else size.width
            val coordinate = if (far) cross - width / 2f else width / 2f
            val start = if (horizontal) Offset(0f, coordinate) else Offset(coordinate, 0f)
            val end = if (horizontal) Offset(size.width, coordinate) else Offset(coordinate, size.height)
            val color = border.rgb?.let { Color(it or 0xff000000.toInt()) } ?: foreground
            drawLine(color, start, end, strokeWidth = width)
        }
        edge(borders.top, horizontal = true, far = false)
        edge(borders.bottom, horizontal = true, far = true)
        edge(borders.left, horizontal = false, far = false)
        edge(borders.right, horizontal = false, far = true)
    }.absolutePadding(
        left = borderInset(borders.left).dp, right = borderInset(borders.right).dp,
        top = borderInset(borders.top).dp, bottom = borderInset(borders.bottom).dp,
    )
    // Word uses the greater of adjacent paragraph gaps rather than adding both.
    val before = (paragraph.beforeTwips.coerceIn(0, 4800) - previousAfterTwips.coerceIn(0, 4800)).coerceAtLeast(0)
    Text(text, modifier = Modifier.fillMaxWidth().then(interaction).padding(
        top = (before / 20f).dp,
        bottom = (paragraph.afterTwips.coerceIn(0, 4800) / 20f).dp,
    ).then(edgeModifier), color = foreground, style = body.copy(fontFamily = FontFamily.SansSerif,
        fontSize = defaultSize.sp, lineHeight = line.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        textIndent = TextIndent(firstLine = first.sp, restLine = continuation.sp),
        textAlign = when (paragraph.alignment) {
            RenderAlignment.Start -> TextAlign.Start; RenderAlignment.End -> TextAlign.End
            RenderAlignment.Left -> TextAlign.Left; RenderAlignment.Right -> TextAlign.Right
            RenderAlignment.Center -> TextAlign.Center; RenderAlignment.Justify -> TextAlign.Justify
        }))
}

internal fun borderInset(border: RenderBorder?): Float =
    border?.let { it.spacePoints + it.widthEighthPoints / 8f } ?: 0f

private val documentSerif = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_bold, FontWeight.Bold),
)

internal fun availableFont(name: String): FontFamily {
    val family = name.trim()
    val fallback = when (documentFontCategory(family)) {
        DocumentFontCategory.SansSerif -> FontFamily.SansSerif
        DocumentFontCategory.Serif -> documentSerif
        DocumentFontCategory.Monospace -> FontFamily.Monospace
        DocumentFontCategory.Cursive -> FontFamily.Cursive
    }
    return runCatching {
        val typeface = Typeface.create(family, Typeface.NORMAL)
        // Android silently substitutes its default Typeface when a name is not installed.
        if (typeface == Typeface.DEFAULT) fallback else FontFamily(typeface)
    }.getOrDefault(fallback)
}

internal fun RenderParagraph.defaultFontSize(bodySize: Float): Float =
    headingLevel?.let { (28f - (it - 1) * 2).coerceAtLeast(18f) } ?: bodySize

internal fun RenderParagraph.lineHeightPoints(defaultSize: Float): Float {
    val maxSize = runs.maxOfOrNull { it.format.sizePoints?.coerceIn(6f, 100f) ?: defaultSize } ?: defaultSize
    val naturalLine = maxSize * 1.2f
    val line = lineSpacing?.takeIf { it > 0 }?.let {
        when (lineRule) {
            "exact" -> it / 20f
            "atLeast" -> maxOf(naturalLine, it / 20f)
            else -> naturalLine * it / 240f
        }.coerceIn(1f, 240f)
    } ?: naturalLine
    return line
}
