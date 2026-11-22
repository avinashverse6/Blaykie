package org.blaykie.nativeapp.library

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
import org.blaykie.nativeapp.zotero.BrowseQuery
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.GroupsLoad
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroCollection
import org.blaykie.nativeapp.zotero.ZoteroGroup
import org.blaykie.nativeapp.zotero.ZoteroGroupsCache
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.blaykie.nativeapp.zotero.ZoteroTag
import org.blaykie.nativeapp.zotero.hasMorePages

internal fun libraryError(prefix: String, result: ZoteroResult<*>): String = when (result) {
    is ZoteroResult.InvalidKey -> "$prefix: HTTP 403"
    is ZoteroResult.HttpError -> "$prefix: HTTP ${result.status}"
    is ZoteroResult.RateLimited -> "$prefix: HTTP 429"
    is ZoteroResult.NetworkFailure -> "$prefix. Check your connection."
    else -> prefix
}

/** Short-lived in-memory page cache keyed by library and query. Nothing is persisted. */
internal class LibraryCache(private val ttlMillis: Long = 120_000) {
    internal data class Entry(val items: List<ZoteroItem>, val totalResults: Int?, val fetchedRaw: Int,
                              val exhausted: Boolean, val atMillis: Long)
    private val entries = mutableMapOf<String, Entry>()
    @Synchronized fun get(key: String, nowMillis: Long): Entry? =
        entries[key]?.takeIf { nowMillis - it.atMillis < ttlMillis }
    @Synchronized fun put(key: String, entry: Entry) { entries[key] = entry }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}

internal data class LibraryState(
    val started: Boolean = false,
    val signedIn: Boolean = true,
    val library: ZoteroLibrary = ZoteroLibrary.Personal,
    val groups: List<ZoteroGroup> = emptyList(),
    val groupsLoaded: Boolean = false,
    val groupsError: String? = null,
    val collections: List<ZoteroCollection> = emptyList(),
    val collectionsLoaded: Boolean = false,
    val collectionsError: String? = null,
    val collectionKey: String? = null,
    val query: String = "",
    val everything: Boolean = false,
    val tag: String? = null,
    val sort: BrowseSort = BrowseSort.DateAdded,
    val descending: Boolean = true,
    val items: List<ZoteroItem> = emptyList(),
    val totalResults: Int? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = true,
    val tags: List<ZoteroTag> = emptyList(),
    val tagsLoading: Boolean = false,
    val tagsError: String? = null,
    val tagsEnd: Boolean = true,
    val selectedItem: ZoteroItem? = null,
) {
    override fun toString() = "LibraryState(redacted)"
}

/** Read-only browsing state. Library data lives only in memory; credentials are read per request. */
internal class LibraryController(private val accounts: AccountStore,
                                 private val clientFactory: () -> ZoteroClient = ::ZoteroClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutable
    private val cache = LibraryCache()
    private var generation = 0
    private var job: Job? = null
    private var fetchedRaw = 0
    /** Set by the UI to persist the last chosen library in app settings. */
    var onLibraryChosen: (Long) -> Unit = {}

    fun start(initialGroupId: Long) {
        if (mutable.value.started) return
        mutable.value = mutable.value.copy(started = true,
            library = if (initialGroupId > 0) ZoteroLibrary.Group(ZoteroGroup(initialGroupId, "")) else ZoteroLibrary.Personal)
        scope.launch {
            val present = withContext(Dispatchers.IO) { accounts.read() is AccountRead.Present }
            mutable.value = mutable.value.copy(signedIn = present)
            if (present) { loadGroups(); reloadAll() }
        }
    }

    private fun loadGroups(force: Boolean = false) {
        val current = generation
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> ZoteroGroupsCache.fetch(clientFactory(), read.account.verified.userId, read.account.key, force)
                    else -> GroupsLoad.Loaded(emptyList())
                }
            }
            if (current != generation) return@launch
            when (result) {
                is GroupsLoad.Loaded -> {
                    val library = (mutable.value.library as? ZoteroLibrary.Group)?.let { chosen ->
                        result.groups.firstOrNull { it.id == chosen.group.id }?.let { ZoteroLibrary.Group(it) } ?: chosen
                    } ?: mutable.value.library
                    mutable.value = mutable.value.copy(groups = result.groups, groupsLoaded = true, groupsError = null, library = library)
                }
                is GroupsLoad.Failed -> mutable.value = mutable.value.copy(groupsLoaded = false, groupsError = result.message)
            }
        }
    }
    fun retryGroups() = loadGroups(force = true)

    fun selectLibrary(library: ZoteroLibrary) {
        if (mutable.value.library == library) return
        onLibraryChosen((library as? ZoteroLibrary.Group)?.group?.id ?: 0)
        generation++
        mutable.value = mutable.value.copy(library = library, collectionKey = null, tag = null,
            collections = emptyList(), collectionsLoaded = false, collectionsError = null, selectedItem = null)
        reloadAll()
    }

    private fun reloadAll() { loadCollections(); loadFirstPage() }

    fun loadCollections(force: Boolean = false) {
        val current = generation
        val library = mutable.value.library
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().collections(library, read.account.verified.userId, read.account.key)
                    else -> ZoteroResult.Success(emptyList<ZoteroCollection>())
                }
            }
            if (current != generation) return@launch
            when (result) {
                is ZoteroResult.Success -> mutable.value = mutable.value.copy(collections = result.value, collectionsLoaded = true, collectionsError = null)
                else -> mutable.value = mutable.value.copy(collectionsLoaded = false,
                    collectionsError = libraryError("Could not load collections", result))
            }
        }
    }

    fun chooseCollection(key: String?) {
        if (mutable.value.collectionKey == key) return
        mutable.value = mutable.value.copy(collectionKey = key, selectedItem = null)
        loadFirstPage()
    }

    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun search() = loadFirstPage()
    fun setEverything(value: Boolean) { mutable.value = mutable.value.copy(everything = value); loadFirstPage() }
    fun setSort(sort: BrowseSort, descending: Boolean) {
        mutable.value = mutable.value.copy(sort = sort, descending = descending); loadFirstPage()
    }
    fun setTag(tag: String?) { mutable.value = mutable.value.copy(tag = tag); loadFirstPage() }

    private fun queryKey(state: LibraryState): String = listOf(state.library.journalValue, state.collectionKey ?: "all",
        state.query.trim(), state.everything, state.tag ?: "", state.sort.name, state.descending).joinToString("|")

    private fun currentQuery(state: LibraryState, start: Int) = BrowseQuery(state.collectionKey, state.query.trim(),
        state.everything, state.tag, state.sort, state.descending, start)

    fun loadFirstPage() {
        val current = ++generation
        job?.cancel()
        val state = mutable.value
        val key = queryKey(state)
        cache.get(key, System.currentTimeMillis())?.let { entry ->
            fetchedRaw = entry.fetchedRaw
            mutable.value = state.copy(items = entry.items, totalResults = entry.totalResults,
                loading = false, loadingMore = false, error = null, endReached = entry.exhausted)
            return
        }
        fetchedRaw = 0
        mutable.value = state.copy(loading = true, error = null, items = emptyList(), endReached = false)
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().browsePage(state.library, read.account.verified.userId, read.account.key, currentQuery(state, 0))
                    else -> ZoteroResult.Success(org.blaykie.nativeapp.zotero.ItemsPage(emptyList(), 0, 0))
                }
            }
            if (current != generation) return@launch
            when (result) {
                is ZoteroResult.Success -> {
                    fetchedRaw = result.value.rawCount
                    val end = !hasMorePages(result.value.totalResults, fetchedRaw, result.value.rawCount, 25)
                    mutable.value = mutable.value.copy(loading = false, items = result.value.items,
                        totalResults = result.value.totalResults, endReached = end, error = null)
                    cache.put(key, LibraryCache.Entry(result.value.items, result.value.totalResults, fetchedRaw, end, System.currentTimeMillis()))
                }
                else -> mutable.value = mutable.value.copy(loading = false, error = libraryError("Could not load items", result))
            }
        }
    }

    fun loadMore() {
        val state = mutable.value
        if (state.loading || state.loadingMore || state.endReached) return
        val current = generation
        val key = queryKey(state)
        mutable.value = state.copy(loadingMore = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().browsePage(state.library, read.account.verified.userId, read.account.key, currentQuery(state, fetchedRaw))
                    else -> ZoteroResult.Success(org.blaykie.nativeapp.zotero.ItemsPage(emptyList(), 0, 0))
                }
            }
            if (current != generation) return@launch
            when (result) {
                is ZoteroResult.Success -> {
                    fetchedRaw += result.value.rawCount
                    val items = mutable.value.items + result.value.items
                    val end = !hasMorePages(result.value.totalResults, fetchedRaw, result.value.rawCount, 25)
                    mutable.value = mutable.value.copy(loadingMore = false, items = items,
                        totalResults = result.value.totalResults, endReached = end, error = null)
                    cache.put(key, LibraryCache.Entry(items, result.value.totalResults, fetchedRaw, end, System.currentTimeMillis()))
                }
                else -> mutable.value = mutable.value.copy(loadingMore = false, error = libraryError("Could not load items", result))
            }
        }
    }

    fun retry() = loadFirstPage()

    fun loadTags(query: String = "") {
        val current = generation
        val library = mutable.value.library
        mutable.value = mutable.value.copy(tagsLoading = true, tagsError = null, tags = emptyList(), tagsEnd = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().tagsPage(library, read.account.verified.userId, read.account.key, query, 0)
                    else -> ZoteroResult.Success(org.blaykie.nativeapp.zotero.TagPage(emptyList(), 0))
                }
            }
            if (current != generation) return@launch
            when (result) {
                is ZoteroResult.Success -> mutable.value = mutable.value.copy(tagsLoading = false, tags = result.value.tags,
                    tagsEnd = result.value.tags.size < 50 || (result.value.totalResults != null && result.value.tags.size >= result.value.totalResults))
                else -> mutable.value = mutable.value.copy(tagsLoading = false, tagsError = libraryError("Could not load tags", result))
            }
        }
    }

    fun loadMoreTags(query: String = "") {
        val state = mutable.value
        if (state.tagsLoading || state.tagsEnd) return
        val current = generation
        val library = state.library
        val startAt = state.tags.size
        mutable.value = state.copy(tagsLoading = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().tagsPage(library, read.account.verified.userId, read.account.key, query, startAt)
                    else -> ZoteroResult.Success(org.blaykie.nativeapp.zotero.TagPage(emptyList(), 0))
                }
            }
            if (current != generation) return@launch
            when (result) {
                is ZoteroResult.Success -> {
                    val all = mutable.value.tags + result.value.tags
                    mutable.value = mutable.value.copy(tagsLoading = false, tags = all,
                        tagsEnd = result.value.tags.size < 50 || (result.value.totalResults != null && all.size >= result.value.totalResults))
                }
                else -> mutable.value = mutable.value.copy(tagsLoading = false, tagsError = libraryError("Could not load tags", result))
            }
        }
    }

    fun selectItem(item: ZoteroItem?) { mutable.value = mutable.value.copy(selectedItem = item) }
    fun close() { generation++; job?.cancel(); scope.cancel() }
}
