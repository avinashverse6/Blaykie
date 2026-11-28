package org.blaykie.nativeapp.library

import org.blaykie.nativeapp.zotero.BrowseNode
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.CollectionNode
import org.blaykie.nativeapp.zotero.ZoteroLibrary

internal sealed interface LibraryTile {
    object All : LibraryTile
    data class Collection(val node: CollectionNode) : LibraryTile
    object Publications : LibraryTile
    object Trash : LibraryTile
}

internal fun libraryRootTiles(library: ZoteroLibrary, roots: List<CollectionNode>, publicationsAvailable: Boolean,
                              expanded: Set<String> = emptySet()): List<LibraryTile> = buildList {
    add(LibraryTile.All)
    fun addCollection(node: CollectionNode) {
        add(LibraryTile.Collection(node))
        if (node.collection.key in expanded) node.children.forEach(::addCollection)
    }
    roots.forEach(::addCollection)
    if (library is ZoteroLibrary.Personal && publicationsAvailable) add(LibraryTile.Publications)
    add(LibraryTile.Trash)
}
internal fun librarySortLabel(sort: BrowseSort) = "Sort: ${sort.label}"
internal fun libraryCollectionActive(node: BrowseNode?) = node is BrowseNode.Collection
internal fun libraryTagActive(tag: String?) = tag != null
internal fun libraryAddVisible(route: LibraryRoute): Boolean = when (route) {
    is LibraryRoute.Root -> route.library is ZoteroLibrary.Personal
    is LibraryRoute.Node -> route.library is ZoteroLibrary.Personal && route.node !is BrowseNode.Publications && route.node !is BrowseNode.Trash
    else -> false
}
internal fun libraryQueryForRequest(query: String): String = query.trim()
