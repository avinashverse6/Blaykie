package org.blaykie.nativeapp.zotero

data class ZoteroCollection(val key: String, val name: String, val parentKey: String?, val numItems: Int?)
data class ZoteroTag(val name: String, val numItems: Int?)
data class TagPage(val tags: List<ZoteroTag>, val totalResults: Int?)
data class ItemsPage(val items: List<ZoteroItem>, val totalResults: Int?, val rawCount: Int)

enum class BrowseSort(val param: String, val label: String) {
    DateAdded("dateAdded", "Date added"),
    Title("title", "Title"),
    Creator("creator", "Creator"),
    Year("date", "Year"),
}

data class BrowseQuery(
    val collectionKey: String? = null,
    val text: String = "",
    val everything: Boolean = false,
    val tag: String? = null,
    val sort: BrowseSort = BrowseSort.DateAdded,
    val descending: Boolean = true,
    val start: Int = 0,
    val limit: Int = 25,
)

/** Request path for a browse page: a collection's top items, or the library's top items. */
internal fun browsePath(library: ZoteroLibrary, userId: Long, collectionKey: String?): List<String> =
    if (collectionKey.isNullOrBlank()) library.itemsPath(userId, top = true)
    else library.basePath(userId) + listOf("collections", collectionKey, "items", "top")

/** Query parameters for a browse page; paging is 25 at a time, driven by start. */
internal fun browseParams(query: BrowseQuery): Map<String, String> = buildMap {
    put("format", "json")
    put("limit", query.limit.coerceIn(1, 100).toString())
    put("start", query.start.coerceAtLeast(0).toString())
    if (query.text.isNotBlank()) {
        put("q", query.text.trim())
        put("qmode", if (query.everything) "everything" else "titleCreatorYear")
    }
    if (!query.tag.isNullOrBlank()) put("tag", query.tag)
    put("sort", query.sort.param)
    put("direction", if (query.descending) "desc" else "asc")
}

/** More pages exist when the last page was full and the server total has not been reached. */
internal fun hasMorePages(totalResults: Int?, fetchedRaw: Int, lastRawCount: Int, limit: Int): Boolean =
    lastRawCount >= limit && (totalResults == null || fetchedRaw < totalResults)

data class CollectionNode(val collection: ZoteroCollection, val depth: Int, val children: List<CollectionNode>)

/** Builds the collection tree from the flat API list (parentCollection refers to a parent key). */
internal fun collectionTree(collections: List<ZoteroCollection>): List<CollectionNode> {
    val byKey = collections.associateBy { it.key }
    fun isRoot(collection: ZoteroCollection): Boolean {
        var parent = collection.parentKey
        val seen = mutableSetOf(collection.key)
        while (parent != null) {
            val parentCollection = byKey[parent] ?: return true // Missing parent: show at root.
            if (!seen.add(parent)) return true // Cycle: show at root rather than recurse forever.
            parent = parentCollection.parentKey
        }
        return collection.parentKey == null || collection.parentKey !in byKey
    }
    fun build(collection: ZoteroCollection, depth: Int, ancestors: Set<String>): CollectionNode {
        val children = collections.filter { it.parentKey == collection.key && it.key !in ancestors }
            .sortedBy { it.name.lowercase() }
            .map { build(it, depth + 1, ancestors + collection.key) }
        return CollectionNode(collection, depth, children)
    }
    return collections.filter(::isRoot).sortedBy { it.name.lowercase() }
        .map { build(it, 0, emptySet()) }
}

/** Flat (collection, depth) rows for display, parents first. */
internal fun flattenTree(nodes: List<CollectionNode>): List<Pair<ZoteroCollection, Int>> =
    nodes.flatMap { node -> listOf(node.collection to node.depth) + flattenTree(node.children) }
