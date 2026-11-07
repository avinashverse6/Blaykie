package org.blaykie.nativeapp.citation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroResult

internal fun hasUsableKey(read: AccountRead): Boolean = read is AccountRead.Present
internal fun insertionPoint(start: Int, end: Int, length: Int): Int = maxOf(start, end).coerceIn(0, length)
internal fun chosenItems(results: List<ZoteroItem>, selected: Set<Int>): List<CitationItem> =
    selected.sorted().mapNotNull { results.getOrNull(it) }.distinctBy { it.key }.map { it.citationItem() }
internal fun searchMessage(result: ZoteroResult<*>): String = when (result) {
    is ZoteroResult.InvalidKey -> "Invalid key or no access to this library."
    is ZoteroResult.RateLimited -> "Zotero rate limit reached. Please try again later."
    is ZoteroResult.NetworkFailure -> "Network error. Check your internet connection."
    is ZoteroResult.MalformedJson -> "Zotero returned invalid data."
    is ZoteroResult.EmptyLibrary -> "No references found."
    is ZoteroResult.HttpError -> "Zotero error: ${result.status}"
    is ZoteroResult.Cancelled -> ""
    is ZoteroResult.Success -> ""
}
internal data class SearchState(
    val open: Boolean = false, val block: Int = 0, val position: Int = 0,
    val query: String = "", val results: List<ZoteroItem> = emptyList(), val selected: Set<Int> = emptySet(),
    val style: String = "parenthetical", val page: String = "", val prefix: String = "",
    val loading: Boolean = false, val hasKey: Boolean = true, val message: String = "",
) {
    override fun toString() = "SearchState(redacted)"
    fun selectedItems(): List<CitationItem> = chosenItems(results, selected)
    fun preview(): String = CitationFormatting.citationText(selectedItems(), style, page, prefix)
}

/** Retains only search data across rotation. Credentials are read for each request, never in state. */
internal class CitationSearchController(private val accounts: AccountStore) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable
    private var job: Job? = null
    private var client: ZoteroClient? = null
    private var generation = 0
    fun open(block: Int, position: Int) {
        close()
        mutable.value = SearchState(open = true, block = block, position = position)
        refreshAccount()
    }
    fun refreshAccount() {
        if (!mutable.value.open) return
        val current = generation
        scope.launch {
            val present = withContext(Dispatchers.IO) { hasUsableKey(accounts.read()) }
            if (current == generation && mutable.value.open) mutable.value = mutable.value.copy(hasKey = present)
        }
    }
    fun close() {
        generation++
        client?.cancel(); client = null; job?.cancel(); job = null
        mutable.value = SearchState()
    }
    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun style(value: String) { mutable.value = mutable.value.copy(style = value) }
    fun page(value: String) { mutable.value = mutable.value.copy(page = value) }
    fun prefix(value: String) { mutable.value = mutable.value.copy(prefix = value) }
    fun toggle(index: Int) {
        val selected = mutable.value.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        mutable.value = mutable.value.copy(selected = selected)
    }
    fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
    fun search() {
        val query = mutable.value.query.trim()
        if (query.isEmpty()) { message("Type something to search."); return }
        client?.cancel(); job?.cancel()
        val current = ++generation
        val requestClient = ZoteroClient(); client = requestClient
        mutable.value = mutable.value.copy(loading = true, results = emptyList(), selected = emptySet(), message = "")
        job = scope.launch {
            val response = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> requestClient.search(read.account.verified.userId, read.account.key, query, 60)
                    else -> null
                }
            }
            if (current != generation || !mutable.value.open) return@launch
            when (response) {
                null -> mutable.value = mutable.value.copy(loading = false, hasKey = false, message = "Add a Zotero key first.")
                is ZoteroResult.Success -> mutable.value = mutable.value.copy(loading = false, hasKey = true, results = response.value, message = "${response.value.size} result(s)")
                is ZoteroResult.Cancelled -> mutable.value = mutable.value.copy(loading = false)
                else -> mutable.value = mutable.value.copy(loading = false, message = searchMessage(response))
            }
        }
    }
    override fun onCleared() { close(); scope.cancel() }
    class Factory(private val accounts: AccountStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CitationSearchController(accounts) as T
    }
}
