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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.blaykie.nativeapp.account.AccountRead
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.crossref.CrossrefClient
import org.blaykie.nativeapp.crossref.CrossrefResult
import org.blaykie.nativeapp.crossref.ItemDraft
import org.blaykie.nativeapp.crossref.buildItemJson
import org.blaykie.nativeapp.crossref.canonicalDoi
import org.blaykie.nativeapp.crossref.crossrefToDraft
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

/** What tapping "+" should do, given the stored key. Pure, so it is covered by unit tests. */
internal enum class AddPermission { Allowed, NeedsOAuth, NeedsManualKey, SignedOut }
internal fun addPermission(signedIn: Boolean, writeAccess: Boolean, isOAuth: Boolean): AddPermission = when {
    !signedIn -> AddPermission.SignedOut
    writeAccess -> AddPermission.Allowed
    isOAuth -> AddPermission.NeedsOAuth
    else -> AddPermission.NeedsManualKey
}

/** State of the Add-by-DOI flow. Item contents live only in memory and are never logged. */
internal data class AddState(
    val open: Boolean = false,
    val doi: String = "",
    val fetching: Boolean = false,
    val draft: ItemDraft? = null,
    val error: String? = null,
    val duplicateTitle: String? = null,
    val saving: Boolean = false,
    val savedKey: String? = null,
    val savedItem: ZoteroItem? = null,
    val collectionKey: String? = null,
    val collectionName: String? = null,
) {
    override fun toString() = "AddState(redacted)"
}

internal fun newWriteToken(random: java.security.SecureRandom = java.security.SecureRandom()): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
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
    val showAddMenu: Boolean = false,
    val showPermissionSheet: Boolean = false,
    val permissionManualKey: Boolean = false,
    val add: AddState = AddState(),
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
                                 private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                                 private val crossrefFactory: () -> CrossrefClient = ::CrossrefClient) {
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

    // --- Add by DOI (personal library only; groups stay read-only) ---

    private var writeToken: String? = null
    private var duplicateConfirmed = false
    private var preconditionRetried = false

    fun addTapped() {
        scope.launch {
            val permission = withContext(Dispatchers.IO) {
                when (val read = accounts.read()) {
                    is AccountRead.Present -> addPermission(true, read.account.verified.permissions.personalWrite,
                        !read.account.username.isNullOrBlank())
                    else -> addPermission(false, writeAccess = false, isOAuth = false)
                }
            }
            mutable.value = when (permission) {
                AddPermission.Allowed -> mutable.value.copy(showAddMenu = true)
                AddPermission.NeedsOAuth -> mutable.value.copy(showPermissionSheet = true, permissionManualKey = false)
                AddPermission.NeedsManualKey -> mutable.value.copy(showPermissionSheet = true, permissionManualKey = true)
                AddPermission.SignedOut -> mutable.value.copy(signedIn = false)
            }
        }
    }

    fun dismissAddMenu() { mutable.value = mutable.value.copy(showAddMenu = false) }
    fun dismissPermissionSheet() { mutable.value = mutable.value.copy(showPermissionSheet = false) }

    fun openAddByDoi() {
        val route = mutable.value.route
        val (key, name) = when (route) {
            is LibraryRoute.Node -> (route.node as? BrowseNode.Collection)?.let {
                it.key to route.title
            } ?: (null to null)
            else -> null to null
        }
        writeToken = null; duplicateConfirmed = false; preconditionRetried = false
        mutable.value = mutable.value.copy(showAddMenu = false,
            add = AddState(open = true, collectionKey = key, collectionName = name))
    }

    fun closeAdd() {
        writeToken = null
        mutable.value = mutable.value.copy(add = AddState())
    }

    fun addAnother() {
        val current = mutable.value.add
        writeToken = null; duplicateConfirmed = false; preconditionRetried = false
        mutable.value = mutable.value.copy(add = AddState(open = true,
            collectionKey = current.collectionKey, collectionName = current.collectionName))
    }

    fun setDoi(value: String) { mutable.value = mutable.value.copy(add = mutable.value.add.copy(doi = value, error = null)) }

    fun fetchDetails() {
        val input = mutable.value.add.doi
        val canonical = canonicalDoi(input)
        if (canonical == null) {
            mutable.value = mutable.value.copy(add = mutable.value.add.copy(error = "That does not look like a DOI."))
            return
        }
        mutable.value = mutable.value.copy(add = mutable.value.add.copy(fetching = true, error = null, draft = null))
        scope.launch {
            val result = withContext(Dispatchers.IO) { crossrefFactory().fetch(canonical) }
            mutable.value = when (result) {
                is CrossrefResult.Success -> mutable.value.copy(
                    add = mutable.value.add.copy(fetching = false,
                        draft = crossrefToDraft(result.work)))
                is CrossrefResult.NotFound -> mutable.value.copy(
                    add = mutable.value.add.copy(fetching = false, error = "DOI not found in Crossref"))
                is CrossrefResult.HttpError -> mutable.value.copy(
                    add = mutable.value.add.copy(fetching = false, error = "Crossref lookup failed: HTTP ${result.status}"))
                is CrossrefResult.NetworkFailure -> mutable.value.copy(
                    add = mutable.value.add.copy(fetching = false, error = "Crossref lookup failed. Check your connection."))
                is CrossrefResult.Malformed -> mutable.value.copy(
                    add = mutable.value.add.copy(fetching = false, error = "Crossref returned invalid data."))
            }
        }
    }

    fun saveDraft(draft: ItemDraft, inCollection: Boolean) {
        if (mutable.value.add.saving) return
        val token = writeToken ?: newWriteToken().also { writeToken = it }
        mutable.value = mutable.value.copy(add = mutable.value.add.copy(saving = true, error = null))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { saveOnce(draft, inCollection, token) }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    val itemJson = buildItemJson(draft,
                        if (inCollection) mutable.value.add.collectionKey else null)
                    val data = (kotlinx.serialization.json.Json.parseToJsonElement(itemJson) as JsonArray)
                        .first() as JsonObject
                    val item = ZoteroItem(outcome.key, data)
                    cache.clear()
                    mutable.value = mutable.value.copy(add = mutable.value.add.copy(
                        saving = false, savedKey = outcome.key, savedItem = item, duplicateTitle = null))
                    if (mutable.value.route is LibraryRoute.Node) loadFirstPage()
                }
                is SaveOutcome.Duplicate -> mutable.value = mutable.value.copy(
                    add = mutable.value.add.copy(saving = false, duplicateTitle = outcome.title))
                is SaveOutcome.PermissionNeeded -> mutable.value = mutable.value.copy(
                    add = mutable.value.add.copy(saving = false),
                    showPermissionSheet = true, permissionManualKey = false)
                is SaveOutcome.Failed -> mutable.value = mutable.value.copy(
                    add = mutable.value.add.copy(saving = false, error = outcome.message))
            }
        }
    }

    fun confirmAddAnyway(draft: ItemDraft, inCollection: Boolean) {
        duplicateConfirmed = true
        mutable.value = mutable.value.copy(add = mutable.value.add.copy(duplicateTitle = null))
        saveDraft(draft, inCollection)
    }

    fun cancelDuplicate() {
        mutable.value = mutable.value.copy(add = mutable.value.add.copy(duplicateTitle = null))
    }

    private sealed interface SaveOutcome {
        data class Saved(val key: String) : SaveOutcome
        data class Duplicate(val title: String) : SaveOutcome
        object PermissionNeeded : SaveOutcome
        data class Failed(val message: String) : SaveOutcome
    }

    /** Runs on a background thread. The same write token is reused across retries of this save. */
    private fun saveOnce(draft: ItemDraft, inCollection: Boolean, token: String): SaveOutcome {
        val read = accounts.read() as? AccountRead.Present ?: return SaveOutcome.PermissionNeeded
        val userId = read.account.verified.userId
        val key = read.account.key
        val client = clientFactory()
        if (!duplicateConfirmed) {
            val doi = draft.fields["DOI"].orEmpty()
            if (doi.isNotBlank()) {
                when (val check = client.findByDoi(userId, key, doi)) {
                    is ZoteroResult.Success -> check.value.firstOrNull()?.let {
                        return SaveOutcome.Duplicate((it.data["title"] as? JsonPrimitive)
                            ?.contentOrNull?.ifBlank { "Untitled" } ?: "Untitled")
                    }
                    is ZoteroResult.InvalidKey -> return SaveOutcome.Failed("Could not check for duplicates: HTTP 403")
                    is ZoteroResult.HttpError -> return SaveOutcome.Failed("Could not check for duplicates: HTTP ${check.status}")
                    is ZoteroResult.RateLimited -> return SaveOutcome.Failed("Could not check for duplicates: HTTP 429")
                    is ZoteroResult.NetworkFailure -> return SaveOutcome.Failed("Could not check for duplicates. Check your connection.")
                    else -> Unit
                }
            }
        }
        val collectionKey = if (inCollection) mutable.value.add.collectionKey else null
        val body = buildItemJson(draft, collectionKey)
        var result = client.createItem(userId, key, body, token)
        if (result is ZoteroClient.WriteResult.PreconditionFailed && !preconditionRetried) {
            preconditionRetried = true
            // Re-fetch (the duplicate check) and retry once with the same write token.
            val doi = draft.fields["DOI"].orEmpty()
            if (doi.isNotBlank()) client.findByDoi(userId, key, doi)
            result = client.createItem(userId, key, body, token)
        }
        return when (result) {
            is ZoteroClient.WriteResult.Success -> SaveOutcome.Saved(result.itemKey)
            is ZoteroClient.WriteResult.Forbidden -> SaveOutcome.PermissionNeeded
            is ZoteroClient.WriteResult.PreconditionFailed -> SaveOutcome.Failed("Could not save: HTTP 412")
            is ZoteroClient.WriteResult.TooLarge -> SaveOutcome.Failed("Could not save: HTTP 413")
            is ZoteroClient.WriteResult.RateLimited -> SaveOutcome.Failed("Could not save: HTTP 429")
            is ZoteroClient.WriteResult.HttpError -> SaveOutcome.Failed("Could not save: HTTP ${result.status}")
            is ZoteroClient.WriteResult.NetworkFailure -> SaveOutcome.Failed("Could not save. Check your connection.")
            is ZoteroClient.WriteResult.Malformed -> SaveOutcome.Failed("Could not save: unreadable reply")
            is ZoteroClient.WriteResult.Cancelled -> SaveOutcome.Failed("Saving was cancelled.")
        }
    }

    fun viewSavedItem() {
        val item = mutable.value.add.savedItem ?: return
        closeAdd()
        when (mutable.value.route) {
            is LibraryRoute.Node -> selectItem(item)
            is LibraryRoute.Root -> { openNode(BrowseNode.All, "All Items"); selectItem(item) }
            else -> Unit
        }
    }

    fun close() {
        groupsGeneration++; collectionsGeneration++; listGeneration++; tagsGeneration++
        listJob?.cancel(); scope.cancel()
    }
}
