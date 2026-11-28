package org.blaykie.nativeapp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import org.blaykie.nativeapp.document.DocumentsScreen
import org.blaykie.nativeapp.document.StoredDocument
import org.blaykie.nativeapp.library.LibraryController
import org.blaykie.nativeapp.library.LibraryScreen

/** Root tabs keep their saved UI state even when another destination is visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeScreen(documents: List<StoredDocument>, message: String, onAdd: () -> Unit,
                        onOpen: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
                        textSize: TextSize, extraStep: HomeExtraStep? = null,
                        libraryController: LibraryController, libraryGroupId: Long,
                        onLibraryChoice: (Long) -> Unit, onSignIn: () -> Unit, onAllowEditing: () -> Unit,
                        selectedTab: RootTab, onSelectTab: (RootTab) -> Unit, screenName: String,
                        settingsContent: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    var documentsRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var libraryRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var settingsRootVersion by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(libraryController) { libraryController.start(libraryGroupId) }
    SideEffect { libraryController.onLibraryChosen = onLibraryChoice }
    val libraryState by libraryController.state.collectAsState()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val modal = selectedTab == RootTab.LIBRARY && (libraryState.showAddMenu || libraryState.showPermissionSheet ||
        libraryState.add.open || libraryState.manual.open)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(targetState = selectedTab, animationSpec = tween(150), label = "Root tab") { tab ->
                val version = when (tab) {
                    RootTab.DOCUMENTS -> documentsRootVersion
                    RootTab.LIBRARY -> libraryRootVersion
                    RootTab.SETTINGS -> settingsRootVersion
                }
                holder.SaveableStateProvider("${tab.name}-$version") {
                    when (tab) {
                        RootTab.DOCUMENTS -> DocumentsScreen(documents, message, onAdd, onOpen, onRename, onDelete,
                            textSize, extraStep)
                        RootTab.LIBRARY -> LibraryScreen(libraryState, libraryController, onSignIn, onAllowEditing)
                        RootTab.SETTINGS -> settingsContent()
                    }
                }
            }
        }
        if (showRootNavigation(screenName, keyboardOpen, modal)) RootNavigationBar(selectedTab) { tab ->
            if (tab == selectedTab) {
                val version = when (tab) {
                    RootTab.DOCUMENTS -> documentsRootVersion++
                    RootTab.LIBRARY -> libraryRootVersion++
                    RootTab.SETTINGS -> settingsRootVersion++
                }
                holder.removeState("${tab.name}-$version")
            }
            onSelectTab(tab)
        }
    }
}
