package org.blaykie.nativeapp.library

import org.blaykie.nativeapp.zotero.BrowseNode
import org.blaykie.nativeapp.zotero.BrowseSort
import org.blaykie.nativeapp.zotero.ZoteroCollection
import org.blaykie.nativeapp.zotero.ZoteroGroup
import org.blaykie.nativeapp.zotero.ZoteroLibrary
import org.blaykie.nativeapp.zotero.collectionTree
import org.junit.Assert.*
import org.junit.Test

class LibraryPresentationTest {
    private val personal = ZoteroLibrary.Personal
    private val group = ZoteroLibrary.Group(ZoteroGroup(7, "Group"))
    private val collections = listOf(
        ZoteroCollection("root", "Rural development", null, 8),
        ZoteroCollection("child", "Nested", "root", null),
    )

    @Test fun rootGridReflectsCollectionsAndPublicationAvailability() {
        val roots = collectionTree(collections)
        val tiles = libraryRootTiles(personal, roots, publicationsAvailable = true)
        assertEquals(LibraryTile.All, tiles.first())
        assertEquals("root", (tiles[1] as LibraryTile.Collection).node.collection.key)
        assertEquals(LibraryTile.Publications, tiles[2])
        assertEquals(LibraryTile.Trash, tiles.last())
        assertFalse(libraryRootTiles(personal, roots, false).contains(LibraryTile.Publications))
        assertFalse(libraryRootTiles(group, roots, true).contains(LibraryTile.Publications))
        assertEquals("child", (libraryRootTiles(personal, roots, true, setOf("root"))[2] as LibraryTile.Collection).node.collection.key)
    }

    @Test fun labelsAndActiveFilters() {
        assertEquals("Sort: Date added", librarySortLabel(BrowseSort.DateAdded))
        assertEquals("Sort: Creator", librarySortLabel(BrowseSort.Creator))
        assertFalse(libraryCollectionActive(BrowseNode.All))
        assertTrue(libraryCollectionActive(BrowseNode.Collection("abc")))
        assertFalse(libraryTagActive(null))
        assertTrue(libraryTagActive("History"))
    }

    @Test fun searchTrimsRequestButNotTypedText() {
        val state = LibraryState(query = "  Thorat ")
        assertEquals("Thorat", libraryQueryForRequest(state.query))
        assertEquals("  Thorat ", state.query)
        assertEquals("", libraryQueryForRequest("  "))
    }

    @Test fun addVisibilityMatchesExistingRoutes() {
        assertFalse(libraryAddVisible(LibraryRoute.Libraries))
        assertTrue(libraryAddVisible(LibraryRoute.Root(personal)))
        assertFalse(libraryAddVisible(LibraryRoute.Root(group)))
        assertTrue(libraryAddVisible(LibraryRoute.Node(personal, BrowseNode.All, "All")))
        assertTrue(libraryAddVisible(LibraryRoute.Node(personal, BrowseNode.Collection("x"), "Collection")))
        assertFalse(libraryAddVisible(LibraryRoute.Node(personal, BrowseNode.Publications, "Publications")))
        assertFalse(libraryAddVisible(LibraryRoute.Node(personal, BrowseNode.Trash, "Trash")))
        assertFalse(libraryAddVisible(LibraryRoute.Node(group, BrowseNode.All, "All")))
    }
}
