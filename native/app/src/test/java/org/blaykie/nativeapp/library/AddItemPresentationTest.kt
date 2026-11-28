package org.blaykie.nativeapp.library

import org.junit.Assert.*
import org.junit.Test

class AddItemPresentationTest {
    @Test fun roleChipsPreserveNamesAndChooseSchemaType() {
        val initial = ManualCreatorRow(role = "author", firstName = "सीमा", lastName = "पटेल")
        val selected = creatorRoleSelected(initial, "editor")
        assertEquals("editor", selected.role)
        assertEquals(initial.firstName, selected.firstName)
        assertEquals(initial.lastName, selected.lastName)
    }
    @Test fun nameModeChangesOnlyMode() {
        val initial = ManualCreatorRow(role = "author", firstName = "A", lastName = "B", name = "Organisation")
        val single = creatorNameModeSelected(initial, true)
        assertTrue(single.singleField)
        assertEquals(initial.name, single.name)
        assertEquals(initial.firstName, single.firstName)
        assertEquals(initial, creatorNameModeSelected(single, false))
    }
    @Test fun footerFollowsTitleAndSavingRules() {
        assertFalse(addFormSaveEnabled(false, false))
        assertFalse(addFormSaveEnabled(true, true))
        assertTrue(addFormSaveEnabled(true, false))
    }
    @Test fun successButtonsDispatchTheirOwnActions() {
        val calls = mutableListOf<String>()
        fun dispatch(action: AddSuccessAction) = routeAddSuccess(action, { calls += "view" }, { calls += "another" }, { calls += "done" })
        dispatch(AddSuccessAction.VIEW_ITEM); dispatch(AddSuccessAction.ADD_ANOTHER); dispatch(AddSuccessAction.DONE)
        assertEquals(listOf("view", "another", "done"), calls)
    }
}
