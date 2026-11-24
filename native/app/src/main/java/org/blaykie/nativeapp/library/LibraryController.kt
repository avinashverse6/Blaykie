package org.blaykie.nativeapp.library

import kotlinx.coroutines.CancellationException
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
import org.blaykie.nativeapp.zotero.SchemaResult
import org.blaykie.nativeapp.zotero.TagPage
import org.blaykie.nativeapp.zotero.TypeSchema
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroCollection
import org.blaykie.nativeapp.zotero.ZoteroGroup
import org.blaykie.nativeapp.zotero.ZoteroGroupsCache
import org.blaykie.nativeapp.zotero.ZoteroItem
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.blaykie.nativeapp.zotero.ZoteroResult
import org.blaykie.nativeapp.zotero.ZoteroSchemaClient
import org.blaykie.nativeapp.zotero.ZoteroSchemaFallback
import org.blaykie.nativeapp.zotero.ZoteroTag
import org.blaykie.nativeapp.zotero.ItemTypeInfo
import org.blaykie.nativeapp.zotero.basicSchema
import org.blaykie.nativeapp.zotero.humaniseSchemaName
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
    val duplicateViaIsbn: Boolean = false,
    val saving: Boolean = false,
    val savedKey: String? = null,
    val savedItem: ZoteroItem? = null,
    val collectionKey: String? = null,
    val collectionName: String? = null,
) {
    override fun toString() = "AddState(redacted)"
}

/** State of the Add manually flow. Item contents live only in memory and are never logged. */
internal data class ManualState(
    val open: Boolean = false,
    val typesLoading: Boolean = false,
    val usingBasicTypes: Boolean = false,
    val types: List<ItemTypeInfo> = emptyList(),
    val schemas: Map<String, TypeSchema> = emptyMap(),
    val schemaLoadingType: String? = null,
    val schemaError: String? = null,
    val schemaErrorType: String? = null,
    val schemaErrorStage: String? = null,
    val schemaErrorStatus: Int? = null,
    val schemaErrorException: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val duplicateTitle: String? = null,
    val duplicateViaIsbn: Boolean = false,
    val savedKey: String? = null,
    val savedItem: ZoteroItem? = null,
    val collectionKey: String? = null,
    val collectionName: String? = null,
) {
    override fun toString() = "ManualState(redacted)"
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
    val manual: ManualState = ManualState(),
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
                                 private val crossrefFactory: () -> CrossrefClient = ::CrossrefClient,
                                 private val schemaFactory: () -> ZoteroSchemaClient = ::ZoteroSchemaClient) {
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

    private fun savedItemFor(draft: ItemDraft, inCollection: Boolean, collectionKey: String?, key: String): ZoteroItem {
        val itemJson = buildItemJson(draft, if (inCollection) collectionKey else null)
        val data = (kotlinx.serialization.json.Json.parseToJsonElement(itemJson) as JsonArray)
            .first() as JsonObject
        return ZoteroItem(key, data)
    }

    fun saveDraft(draft: ItemDraft, inCollection: Boolean) {
        if (mutable.value.add.saving) return
        val token = writeToken ?: newWriteToken().also { writeToken = it }
        val collectionKey = if (inCollection) mutable.value.add.collectionKey else null
        mutable.value = mutable.value.copy(add = mutable.value.add.copy(saving = true, error = null))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { saveOnce(draft, collectionKey, token) }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    val item = savedItemFor(draft, inCollection, mutable.value.add.collectionKey, outcome.key)
                    cache.clear()
                    mutable.value = mutable.value.copy(add = mutable.value.add.copy(
                        saving = false, savedKey = outcome.key, savedItem = item, duplicateTitle = null))
                    if (mutable.value.route is LibraryRoute.Node) loadFirstPage()
                }
                is SaveOutcome.Duplicate -> mutable.value = mutable.value.copy(
                    add = mutable.value.add.copy(saving = false, duplicateTitle = outcome.title,
                        duplicateViaIsbn = outcome.viaIsbn))
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
        data class Duplicate(val title: String, val viaIsbn: Boolean) : SaveOutcome
        object PermissionNeeded : SaveOutcome
        data class Failed(val message: String) : SaveOutcome
    }

    private sealed interface DuplicateCheck {
        object None : DuplicateCheck
        data class Found(val title: String, val viaIsbn: Boolean) : DuplicateCheck
        data class Failed(val message: String) : DuplicateCheck
    }

    /**
     * The duplicate check shared by Add by DOI and Add manually: it runs only when a
     * DOI (preferred) or an ISBN was entered. Runs on a background thread.
     */
    private fun checkDuplicate(client: ZoteroClient, userId: Long, key: String, draft: ItemDraft): DuplicateCheck {
        val doi = draft.fields["DOI"].orEmpty().trim()
        val isbn = draft.fields["ISBN"].orEmpty().trim()
        val (query, viaIsbn) = when {
            doi.isNotBlank() -> doi to false
            isbn.isNotBlank() -> isbn to true
            else -> return DuplicateCheck.None
        }
        val result = if (viaIsbn) client.findByIsbn(userId, key, query) else client.findByDoi(userId, key, query)
        return when (result) {
            is ZoteroResult.Success -> result.value.firstOrNull()?.let { item ->
                DuplicateCheck.Found((item.data["title"] as? JsonPrimitive)
                    ?.contentOrNull?.ifBlank { "Untitled" } ?: "Untitled", viaIsbn)
            } ?: DuplicateCheck.None
            is ZoteroResult.InvalidKey -> DuplicateCheck.Failed("Could not check for duplicates: HTTP 403")
            is ZoteroResult.HttpError -> DuplicateCheck.Failed("Could not check for duplicates: HTTP ${result.status}")
            is ZoteroResult.RateLimited -> DuplicateCheck.Failed("Could not check for duplicates: HTTP 429")
            is ZoteroResult.NetworkFailure -> DuplicateCheck.Failed("Could not check for duplicates. Check your connection.")
            else -> DuplicateCheck.None
        }
    }

    /** Runs on a background thread. The same write token is reused across retries of this save. */
    private fun saveOnce(draft: ItemDraft, collectionKey: String?, token: String): SaveOutcome {
        val read = accounts.read() as? AccountRead.Present ?: return SaveOutcome.PermissionNeeded
        val userId = read.account.verified.userId
        val key = read.account.key
        val client = clientFactory()
        if (!duplicateConfirmed) {
            when (val check = checkDuplicate(client, userId, key, draft)) {
                is DuplicateCheck.Found -> return SaveOutcome.Duplicate(check.title, check.viaIsbn)
                is DuplicateCheck.Failed -> return SaveOutcome.Failed(check.message)
                DuplicateCheck.None -> Unit
            }
        }
        val body = buildItemJson(draft, collectionKey)
        var result = client.createItem(userId, key, body, token)
        if (result is ZoteroClient.WriteResult.PreconditionFailed && !preconditionRetried) {
            preconditionRetried = true
            // Re-fetch (the duplicate check) and retry once with the same write token.
            checkDuplicate(client, userId, key, draft)
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
        showSavedItem(item)
    }

    private fun showSavedItem(item: ZoteroItem) {
        when (mutable.value.route) {
            is LibraryRoute.Node -> selectItem(item)
            is LibraryRoute.Root -> { openNode(BrowseNode.All, "All Items"); selectItem(item) }
            else -> Unit
        }
    }

    // --- Add manually (personal library only; reuses the save pipeline above) ---

    private val schemaClient: ZoteroSchemaClient by lazy { schemaFactory() }
    private var typesGeneration = 0
    private var schemaGeneration = 0
    private var typesJob: Job? = null
    private var schemaJob: Job? = null

    fun openAddManually() {
        val route = mutable.value.route
        val (key, name) = when (route) {
            is LibraryRoute.Node -> (route.node as? BrowseNode.Collection)?.let {
                it.key to route.title
            } ?: (null to null)
            else -> null to null
        }
        writeToken = null; duplicateConfirmed = false; preconditionRetried = false
        val loadedTypes = mutable.value.manual.types
        mutable.value = mutable.value.copy(showAddMenu = false,
            manual = ManualState(open = true, types = loadedTypes, collectionKey = key, collectionName = name))
        if (loadedTypes.isEmpty()) loadManualTypes()
    }

    fun closeManual() {
        writeToken = null
        mutable.value = mutable.value.copy(manual = ManualState(types = mutable.value.manual.types,
            usingBasicTypes = mutable.value.manual.usingBasicTypes, schemas = mutable.value.manual.schemas))
    }

    fun addAnotherManual() {
        val current = mutable.value.manual
        writeToken = null; duplicateConfirmed = false; preconditionRetried = false
        mutable.value = mutable.value.copy(manual = ManualState(open = true, types = current.types,
            usingBasicTypes = current.usingBasicTypes, schemas = current.schemas,
            collectionKey = current.collectionKey, collectionName = current.collectionName))
    }

    fun loadManualTypes() {
        if (mutable.value.manual.types.isNotEmpty()) return
        val current = ++typesGeneration
        typesJob?.cancel()
        mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(typesLoading = true))
        typesJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { schemaClient.itemTypes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave the chooser on a spinner: fall back to the built-in type list.
                if (current != typesGeneration) return@launch
                mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(
                    typesLoading = false, types = ZoteroSchemaFallback.types, usingBasicTypes = true))
                return@launch
            }
            if (current != typesGeneration) return@launch
            val manual = mutable.value.manual
            mutable.value = mutable.value.copy(manual = when (result) {
                is SchemaResult.Success<*> -> manual.copy(typesLoading = false,
                    types = result.value as List<ItemTypeInfo>, usingBasicTypes = result.basic)
                // The client already falls back to the basic list; reaching here means
                // even that failed, which cannot happen — keep the flags consistent.
                else -> manual.copy(typesLoading = false, types = ZoteroSchemaFallback.types,
                    usingBasicTypes = true)
            })
        }
    }

    /** Human-readable message for a failed schema load, including the failing stage and HTTP status. */
    private fun schemaErrorText(label: String, stage: String, status: Int?, exception: String?): String {
        val base = "Could not load fields for $label: step $stage"
        return base + when {
            status != null -> ", HTTP $status"
            exception != null -> ": $exception"
            else -> ""
        }
    }

    /** Exact, key-free details for the "Copy details" button (stage, HTTP status, exception class). */
    private fun schemaCopyDetails(stage: String, status: Int?, exception: String?): String =
        "stage=$stage; httpStatus=${status ?: "none"}; exception=${exception ?: "none"}"

    /**
     * Starts (or restarts) the schema load for [type]. Re-entrant by design: every call cancels
     * any load still in flight and starts a fresh one, so changing the type, tapping Retry,
     * rotating the phone or reopening the sheet always produces a new load. A failed load is
     * never cached, so this always makes a request again for a type that is not loaded yet.
     */
    fun loadManualSchema(type: String) {
        val current = ++schemaGeneration
        schemaJob?.cancel()
        val manual = mutable.value.manual
        if (manual.schemas.containsKey(type)) {
            // Already loaded: just clear any stale loading/error state for this type.
            if (manual.schemaLoadingType != null || manual.schemaError != null) {
                mutable.value = mutable.value.copy(manual = manual.copy(
                    schemaLoadingType = null, schemaError = null, schemaErrorType = null,
                    schemaErrorStage = null, schemaErrorStatus = null, schemaErrorException = null))
            }
            return
        }
        // The label comes from the type list already in memory; no extra request for it.
        val label = manual.types.firstOrNull { it.type == type }?.label ?: humaniseSchemaName(type)
        mutable.value = mutable.value.copy(manual = manual.copy(
            schemaLoadingType = type, schemaError = null, schemaErrorType = null,
            schemaErrorStage = null, schemaErrorStatus = null, schemaErrorException = null))
        schemaJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { schemaClient.typeSchema(type, label) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A crash inside the client must still end the spinner, with a stage to show.
                SchemaResult.Failed("schema", null, e.javaClass.simpleName)
            }
            if (current != schemaGeneration) return@launch
            val manualNow = mutable.value.manual
            mutable.value = mutable.value.copy(manual = when (result) {
                is SchemaResult.Success<*> -> manualNow.copy(schemaLoadingType = null,
                    schemas = manualNow.schemas + (type to result.value as TypeSchema))
                is SchemaResult.Failed -> manualNow.copy(schemaLoadingType = null,
                    schemaError = schemaErrorText(label, result.stage, result.status, result.exception),
                    schemaErrorType = type, schemaErrorStage = result.stage,
                    schemaErrorStatus = result.status, schemaErrorException = result.exception)
                else -> manualNow.copy(schemaLoadingType = null,
                    schemaError = schemaErrorText(label, "schema", null, "unknown"),
                    schemaErrorType = type, schemaErrorStage = "schema",
                    schemaErrorStatus = null, schemaErrorException = "unknown")
            })
        }
    }

    /**
     * Escape hatch: open the form with the built-in classic fields (for the 11 common types)
     * or a minimal title-plus-author schema otherwise, so the user can always continue even
     * when the schema API is unavailable. Never blocks and never logs anything.
     */
    fun useBasicManualForm(type: String) {
        val manual = mutable.value.manual
        if (manual.schemaLoadingType == type) {
            ++schemaGeneration
            schemaJob?.cancel()
        }
        if (manual.schemas.containsKey(type)) {
            mutable.value = mutable.value.copy(manual = manual.copy(
                schemaLoadingType = null, schemaError = null, schemaErrorType = null,
                schemaErrorStage = null, schemaErrorStatus = null, schemaErrorException = null))
            return
        }
        val label = manual.types.firstOrNull { it.type == type }?.label ?: humaniseSchemaName(type)
        val schema = ZoteroSchemaFallback.schema(type) ?: basicSchema(type, label)
        mutable.value = mutable.value.copy(manual = manual.copy(
            schemaLoadingType = null, schemaError = null, schemaErrorType = null,
            schemaErrorStage = null, schemaErrorStatus = null, schemaErrorException = null,
            schemas = manual.schemas + (type to schema)))
    }

    /** Builds the copy-details text for the current schema error (no keys or item contents). */
    fun manualSchemaCopyDetails(): String? {
        val manual = mutable.value.manual
        return manual.schemaErrorStage?.let { schemaCopyDetails(it, manual.schemaErrorStatus, manual.schemaErrorException) }
    }

    fun saveManualDraft(draft: ItemDraft, inCollection: Boolean) {
        if (mutable.value.manual.saving) return
        val token = writeToken ?: newWriteToken().also { writeToken = it }
        val collectionKey = if (inCollection) mutable.value.manual.collectionKey else null
        mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(saving = true, error = null))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { saveOnce(draft, collectionKey, token) }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    val item = savedItemFor(draft, inCollection, mutable.value.manual.collectionKey, outcome.key)
                    cache.clear()
                    mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(
                        saving = false, savedKey = outcome.key, savedItem = item, duplicateTitle = null))
                    if (mutable.value.route is LibraryRoute.Node) loadFirstPage()
                }
                is SaveOutcome.Duplicate -> mutable.value = mutable.value.copy(
                    manual = mutable.value.manual.copy(saving = false, duplicateTitle = outcome.title,
                        duplicateViaIsbn = outcome.viaIsbn))
                is SaveOutcome.PermissionNeeded -> mutable.value = mutable.value.copy(
                    manual = mutable.value.manual.copy(saving = false),
                    showPermissionSheet = true, permissionManualKey = false)
                is SaveOutcome.Failed -> mutable.value = mutable.value.copy(
                    manual = mutable.value.manual.copy(saving = false, error = outcome.message))
            }
        }
    }

    fun confirmManualAddAnyway(draft: ItemDraft, inCollection: Boolean) {
        duplicateConfirmed = true
        mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(duplicateTitle = null))
        saveManualDraft(draft, inCollection)
    }

    fun cancelManualDuplicate() {
        mutable.value = mutable.value.copy(manual = mutable.value.manual.copy(duplicateTitle = null))
    }

    fun viewManualSavedItem() {
        val item = mutable.value.manual.savedItem ?: return
        closeManual()
        showSavedItem(item)
    }

    fun close() {
        groupsGeneration++; collectionsGeneration++; listGeneration++; tagsGeneration++
        typesGeneration++; schemaGeneration++
        listJob?.cancel(); scope.cancel()
    }
}
