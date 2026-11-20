package org.blaykie.nativeapp.render

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.document.DocumentPosition
import org.blaykie.nativeapp.document.DocumentView
import org.blaykie.nativeapp.docx.DocxProject

internal data class MobilePage(val source: File, val items: List<MobilePageItem>, val setup: PageSetup,
                               val bibliographyBlocks: Set<Int> = emptySet())

/** The page survives trips into the editor. Only changed paragraphs are parsed on return. */
internal class MobileDocumentState(position: DocumentPosition = DocumentPosition(), initialView: DocumentView = DocumentView.Mobile) {
    val scroll = LazyListState()
    val printScroll = LazyListState()
    val printPan = ScrollState(0)
    var printZoom by mutableFloatStateOf(1f)
    val printMeasurements = PrintMeasurements()
    private var rawPage: MobilePage? = null
    var view by mutableStateOf(initialView)
        private set
    private var printPages: List<PrintPage> = emptyList()
    private var printPixelsPerPoint = 1f

    fun switchView(next: DocumentView) {
        if (next == view) return
        focus = DocumentPosition(position().paragraphIndex)
        view = next
    }

    fun printScale(pixelsPerPoint: Float) { printPixelsPerPoint = pixelsPerPoint }

    suspend fun showPrintPages(pages: List<PrintPage>, topMarginPoints: Float) {
        val target = focus ?: if (printPages.isNotEmpty() && printPages != pages) position() else null
        printPages = pages
        if (target == null || pages.isEmpty() || view != DocumentView.Print) return
        val index = pages.indexOfFirst { sheet -> sheet.rows.any { it.blockIndex >= target.paragraphIndex } }
            .takeIf { it >= 0 } ?: pages.lastIndex
        val row = pages[index].rows.firstOrNull { it.blockIndex >= target.paragraphIndex }
        val offset = if (row == null || row == pages[index].rows.firstOrNull()) 0 else
            row.printOffsetPx(topMarginPoints, printPixelsPerPoint)
        printScroll.scrollToItem(index, offset)
        focus = null
    }
    var page by mutableStateOf<MobilePage?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var renderedRevision by mutableIntStateOf(-1)
        private set
    private var focus by mutableStateOf<DocumentPosition?>(position)
    private val dirty = linkedSetOf<Int>()
    private var reload = true

    fun changed(block: Int? = null) {
        if (block == null) reload = true else dirty.add(block)
        revision++
    }

    fun focusParagraph(block: Int) {
        focus = DocumentPosition(block)
    }

    fun position(): DocumentPosition {
        focus?.let { return it }
        if (view == DocumentView.Print) {
            val rows = printPages.getOrNull(printScroll.firstVisibleItemIndex)?.rows.orEmpty()
            return DocumentPosition(printRowAtOffset(rows, printScroll.firstVisibleItemScrollOffset,
                (page?.setup?.topTwips ?: 0) / 20f, printPixelsPerPoint)?.blockIndex ?: 0)
        }
        val items = page?.items.orEmpty()
        val index = scroll.firstVisibleItemIndex
        return DocumentPosition(items.getOrNull(index)?.blockIndex ?: items.lastOrNull()?.blockIndex ?: 0,
            if (index < items.size) scroll.firstVisibleItemScrollOffset else 0)
    }

    var hiddenBibliography = false
        private set

    suspend fun update(project: DocxProject, hideBibliography: Boolean) {
        val previous = rawPage
        val startRevision = revision
        val full = if (reload || previous == null) project.renderInput() else null
        val inputs = if (full == null) dirty.associateWith { project.renderInput(it) } else emptyMap()
        if (full == null && inputs.isEmpty() && hideBibliography == hiddenBibliography) return
        val next = withContext(Dispatchers.Default) {
            if (full != null) full.read().let { MobilePage(it.source, it.pageItems(), it.pageSetup, it.bibliographyBlocks) }
            else {
                checkNotNull(previous)
                if (inputs.isEmpty()) return@withContext previous
                val groups = inputs.mapValues { (block, input) ->
                    val parsed = input.read()
                    val old = previous.items.filterIsInstance<MobilePageItem.Block>()
                        .firstOrNull { it.blockIndex == block }?.content as? RenderParagraph
                    parsed.blocks.map { item ->
                        // Editing cannot change numbering definitions or numbering membership.
                        val updated = if (item is RenderParagraph && item.list != null && old?.list != null) item.copy(list = old.list) else item
                        MobilePageItem.Block(block, updated, updated is RenderParagraph && 0 in parsed.editableBlocks)
                    }
                }
                val replaced = mutableSetOf<Int>()
                val items = previous.items.flatMap { item ->
                    val updated = groups[item.blockIndex]
                    if (updated == null) listOf(item)
                    else if (replaced.add(item.blockIndex)) updated else emptyList()
                }
                previous.copy(items = items)
            }
        }
        if (revision != startRevision) return
        hiddenBibliography = hideBibliography
        renderedRevision = startRevision
        rawPage = next
        page = if (!hideBibliography || next.bibliographyBlocks.isEmpty()) next else
            next.copy(items = next.items.filterNot { it.blockIndex in next.bibliographyBlocks })
        reload = false
        dirty.clear()
    }

    suspend fun restoreMobileFocus(page: MobilePage) {
        if (view != DocumentView.Mobile) return
        val position = focus ?: return
        if (page.items.isNotEmpty()) {
            val index = page.items.indexOfFirst { it.blockIndex >= position.paragraphIndex }
                .takeIf { it >= 0 } ?: page.items.lastIndex
            val offset = if (page.items[index].blockIndex == position.paragraphIndex) position.scrollOffset else 0
            scroll.scrollToItem(index, offset)
        }
        focus = null
    }
}
