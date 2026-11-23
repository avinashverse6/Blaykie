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
import org.blaykie.nativeapp.zotero.BrowseNode
import org.blaykie.nativeapp.zotero.BrowseQuery
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.GroupsLoad
import org.blaykie.nativeapp.zotero.ItemsPage
import org.blaykie.nativeapp.zotero.TagPage
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

/** Navigation inside the Library tab; the stack lives only in memory for this session. */
internal sealed interface LibraryRoute {
    object Libraries : LibraryRoute
    data class Root(val library: ZoteroLibrary) : LibraryRoute
    data class Node(val library: ZoteroLibrary, val node: BrowseNode, val title: String) : LibraryRoute
    data class Detail(val library: ZoteroLibrary, val node: BrowseNode, val title: String, val item: ZoteroItem) : LibraryRoute
}

internal fun LibraryRoute.libraryOrNull(): ZoteroLibrary? = when (this) {
    is LibraryRoute.Root -> library
    is LibraryRoute.Node -> library
    is LibraryRoute.Detail -> library
    is LibraryRoute.Libraries -> null
}

internal data class LibraryState(
    val started: Boolean = false,
    val signedIn: Boolean = true,
    val route: LibraryRoute = LibraryRoute.Libraries,
    val groups: List<ZoteroGroup> = emptyList(),
    val groupsLoaded: Boolean = false,
    val groupsError: String? = null,
    val collections: List<ZoteroCollection> = emptyList(),
    val collectionsLoaded: Boolean = false,
    val collectionsError: String? = null,
    val collectionsLibrary: ZoteroLibrary? = null,
    val expandedCollections: Set<String> = emptySet(),
    val publicationsAvailable: Boolean = false,
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
    val openTagsRequested: Int = 0,
) {
    override fun toString() = "LibraryState(redacted)"
    val library: ZoteroLibrary? get() = route.libraryOrNull()
    val node: BrowseNode? get() = when (route) {
        is LibraryRoute.Node -> route.node
        is LibraryRoute.Detail -> route.node
        else -> null
    }
}

/**
 * Read-only browsing state. The back stack, caches and last node live only in memory.
 * Each load type has its own generation counter, so a fast item load can never
 * invalidate a slower groups or collections result (and vice versa).
 */
internal class LibraryController(private val accounts: AccountStore,
                                 private val clientFactory: () -> ZoteroClient = ::ZoteroClient,
                                 private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val mutable = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutable
    private val cache = LibraryCache()
    private val stack = mutableListOf<LibraryRoute>(LibraryRoute.Libraries)
    private var groupsGeneration = 0
    private var collectionsGeneration = 0
    private var listGeneration = 0
    private var tagsGeneration = 0
    private var listJob: Job? = null
    private var fetchedRaw = 0
    /** Set by the UI to persist the last chosen library in app settings. */
    var onLibraryChosen: (Long) -> Unit = {}

    fun start(initialGroupId: Long) {
        if (mutable.value.started) return
        mutable.value = mutable.value.copy(started = true)
        scope.launch {
            val present = withContext(Dispatchers.IO) { accounts.read() is AccountRead.Present }
            mutable.value = mutable.value.copy(signedIn = present)
            if (present) loadGroups()
        }
    }

    // --- Groups: the same loader, cache, error text and Retry as the citation sheet. ---

    private fun loadGroups(force: Boolean = false) {
        val current = ++groupsGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> ZoteroGroupsCache.fetch(clientFactory(), read.account.verified.userId, read.account.key, force)
                    else -> GroupsLoad.Loaded(emptyList())
                }
            }
            if (current != groupsGeneration) return@launch
            when (result) {
                is GroupsLoad.Loaded -> mutable.value = mutable.value.copy(groups = result.groups, groupsLoaded = true, groupsError = null)
                is GroupsLoad.Failed -> mutable.value = mutable.value.copy(groupsLoaded = false, groupsError = result.message)
            }
        }
    }
    fun retryGroups() = loadGroups(force = true)

    // --- Navigation ---

    private fun publish(route: LibraryRoute) {
        mutable.value = mutable.value.copy(route = route)
    }

    fun openLibrary(library: ZoteroLibrary) {
        onLibraryChosen((library as? ZoteroLibrary.Group)?.group?.id ?: 0)
        stack.clear(); stack += LibraryRoute.Libraries; stack += LibraryRoute.Root(library)
        publish(stack.last())
        mutable.value = resetList(mutable.value.copy(collections = emptyList(), collectionsLoaded = false,
            collectionsError = null, collectionsLibrary = null, expandedCollections = emptySet(),
            publicationsAvailable = false, tag = null))
        loadCollections(library)
        if (library is ZoteroLibrary.Personal) probePublications()
    }

    fun openNode(node: BrowseNode, title: String) {
        val library = mutable.value.library ?: return
        stack += LibraryRoute.Node(library, node, title)
        publish(stack.last())
        mutable.value = resetList(mutable.value)
        loadFirstPage()
    }

    fun openCollection(collection: ZoteroCollection) = openNode(BrowseNode.Collection(collection.key), collection.name)

    fun selectItem(item: ZoteroItem) {
        val route = mutable.value.route as? LibraryRoute.Node ?: return
        stack += LibraryRoute.Detail(route.library, route.node, route.title, item)
        publish(stack.last())
    }

    /** Returns false when already at the Libraries screen (the caller may then leave the tab). */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        publish(stack.last())
        return true
    }

    fun toggleExpanded(key: String) {
        val expanded = mutable.value.expandedCollections
        mutable.value = mutable.value.copy(expandedCollections = if (key in expanded) expanded - key else expanded + key)
    }

    private fun resetList(state: LibraryState): LibraryState = state.copy(items = emptyList(), error = null,
        loading = false, loadingMore = false, endReached = true, totalResults = null)

    // --- Collections and publications ---

    private fun loadCollections(library: ZoteroLibrary, force: Boolean = false) {
        val current = ++collectionsGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().collections(library, read.account.verified.userId, read.account.key)
                    else -> ZoteroResult.Success(emptyList<ZoteroCollection>())
                }
            }
            if (current != collectionsGeneration || mutable.value.library != library) return@launch
            when (result) {
                is ZoteroResult.Success -> mutable.value = mutable.value.copy(collections = result.value,
                    collectionsLoaded = true, collectionsError = null, collectionsLibrary = library)
                else -> mutable.value = mutable.value.copy(collectionsLoaded = false, collectionsLibrary = library,
                    collectionsError = libraryError("Could not load collections", result))
            }
        }
    }
    fun retryCollections() {
        mutable.value.library?.let { loadCollections(it, force = true) }
    }

    private fun probePublications() {
        val current = collectionsGeneration
        scope.launch {
            val available = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().publicationsAvailable(read.account.verified.userId, read.account.key)
                    else -> false
                }
            }
            if (current != collectionsGeneration) return@launch
            if (mutable.value.library is ZoteroLibrary.Personal) {
                mutable.value = mutable.value.copy(publicationsAvailable = available)
            }
        }
    }

    // --- Item lists, scoped to the current node ---

    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun search() = loadFirstPage()
    fun setEverything(value: Boolean) { mutable.value = mutable.value.copy(everything = value); loadFirstPage() }
    fun setSort(sort: BrowseSort, descending: Boolean) {
        mutable.value = mutable.value.copy(sort = sort, descending = descending); loadFirstPage()
    }
    fun setTag(tag: String?) { mutable.value = mutable.value.copy(tag = tag); loadFirstPage() }

    private fun queryKey(state: LibraryState): String = listOf(state.library?.journalValue ?: "?", state.node?.cacheKey ?: "?",
        state.query.trim(), state.everything, state.tag ?: "", state.sort.name, state.descending).joinToString("|")

    private fun currentQuery(state: LibraryState, start: Int) = BrowseQuery(state.node ?: BrowseNode.All,
        state.query.trim(), state.everything, state.tag, state.sort, state.descending, start)

    fun loadFirstPage() {
        val route = mutable.value.route as? LibraryRoute.Node ?: return
        val current = ++listGeneration
        listJob?.cancel()
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
        listJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().browsePage(route.library, read.account.verified.userId, read.account.key, currentQuery(state, 0))
                    else -> ZoteroResult.Success(ItemsPage(emptyList(), 0, 0))
                }
            }
            if (current != listGeneration) return@launch
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
        val route = mutable.value.route as? LibraryRoute.Node ?: return
        val state = mutable.value
        if (state.loading || state.loadingMore || state.endReached) return
        val current = listGeneration
        val key = queryKey(state)
        mutable.value = state.copy(loadingMore = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().browsePage(route.library, read.account.verified.userId, read.account.key, currentQuery(state, fetchedRaw))
                    else -> ZoteroResult.Success(ItemsPage(emptyList(), 0, 0))
                }
            }
            if (current != listGeneration) return@launch
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

    // --- Tags ---

    fun requestTags() {
        mutable.value = mutable.value.copy(openTagsRequested = mutable.value.openTagsRequested + 1)
        loadTags("")
    }

    fun loadTags(query: String = "") {
        val route = mutable.value.route
        val library = route.libraryOrNull() ?: return
        val current = ++tagsGeneration
        mutable.value = mutable.value.copy(tagsLoading = true, tagsError = null, tags = emptyList(), tagsEnd = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().tagsPage(library, read.account.verified.userId, read.account.key, query, 0)
                    else -> ZoteroResult.Success(TagPage(emptyList(), 0))
                }
            }
            if (current != tagsGeneration) return@launch
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
        val library = state.library ?: return
        val current = tagsGeneration
        val startAt = state.tags.size
        mutable.value = state.copy(tagsLoading = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> clientFactory().tagsPage(library, read.account.verified.userId, read.account.key, query, startAt)
                    else -> ZoteroResult.Success(TagPage(emptyList(), 0))
                }
            }
            if (current != tagsGeneration) return@launch
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

    fun close() {
        groupsGeneration++; collectionsGeneration++; listGeneration++; tagsGeneration++
        listJob?.cancel(); scope.cancel()
    }
}
