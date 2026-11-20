package org.blaykie.nativeapp.render

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.document.DocumentPosition
import org.blaykie.nativeapp.docx.DocxProject

internal data class MobilePage(val source: File, val items: List<MobilePageItem>)

/** The page survives trips into the editor. Only changed paragraphs are parsed on return. */
internal class MobileDocumentState(position: DocumentPosition = DocumentPosition()) {
    val scroll = LazyListState()
    var page by mutableStateOf<MobilePage?>(null)
        private set
    var revision by mutableIntStateOf(0)
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
        val items = page?.items.orEmpty()
        val index = scroll.firstVisibleItemIndex
        return DocumentPosition(items.getOrNull(index)?.blockIndex ?: items.lastOrNull()?.blockIndex ?: 0,
            if (index < items.size) scroll.firstVisibleItemScrollOffset else 0)
    }

    private var hiddenBibliography = false

    suspend fun update(project: DocxProject, hideBibliography: Boolean) {
        if (hideBibliography != hiddenBibliography) reload = true
        val previous = page
        val startRevision = revision
        val inputs = if (reload || previous == null) emptyMap() else dirty.associateWith { project.renderInput(it) }
        if (!reload && previous != null && inputs.isEmpty()) {
            restoreFocus(previous)
            return
        }
        val full = if (inputs.isEmpty()) project.renderInput() else null
        val next = withContext(Dispatchers.Default) {
            if (full != null) full.read(hideBibliography = hideBibliography).let { MobilePage(it.source, it.pageItems()) }
            else {
                checkNotNull(previous)
                val groups = inputs.mapValues { (block, input) ->
                    val parsed = input.read()
                    val old = previous.items.filterIsInstance<MobilePageItem.Block>()
                        .firstOrNull { it.blockIndex == block }?.content as? RenderParagraph
                    parsed.blocks.map { item ->
                        // Editing cannot change numbering definitions or numbering membership.
                        val updated = if (item is RenderParagraph && item.list != null && old?.list != null) item.copy(list = old.list) else item
                        MobilePageItem.Block(block, updated)
                    }
                }
                val replaced = mutableSetOf<Int>()
                val items = previous.items.flatMap { item ->
                    val updated = groups[item.blockIndex]
                    if (updated == null) listOf(item)
                    else if (replaced.add(item.blockIndex)) updated else emptyList()
                }
                MobilePage(previous.source, items)
            }
        }
        if (revision != startRevision) return
        hiddenBibliography = hideBibliography
        page = next
        reload = false
        dirty.clear()
        restoreFocus(next)
    }

    private suspend fun restoreFocus(page: MobilePage) {
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
