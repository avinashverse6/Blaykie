package org.blaykie.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.document.DocumentsScreen
import org.blaykie.nativeapp.document.StoredDocument
import org.blaykie.nativeapp.library.LibraryController
import org.blaykie.nativeapp.library.LibraryScreen

/** Home with the Documents | Library tabs; the Library tab is a read-only browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(documents: List<StoredDocument>, message: String, onAdd: () -> Unit,
                        onOpen: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
                        onSettings: () -> Unit, textSize: TextSize, extraStep: HomeExtraStep? = null,
                        accountStore: AccountStore, libraryGroupId: Long,
                        onLibraryChoice: (Long) -> Unit, onSignIn: () -> Unit, onAllowEditing: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val libraryController = remember { LibraryController(accountStore) }
    DisposableEffect(Unit) { onDispose { libraryController.close() } }
    LaunchedEffect(Unit) { libraryController.start(libraryGroupId) }
    LaunchedEffect(Unit) { libraryController.onLibraryChosen = onLibraryChoice }
    val libraryState by libraryController.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.large, vertical = Space.small)) {
            SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("Documents", style = MaterialTheme.typography.titleSmall)
            }
            SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Library", style = MaterialTheme.typography.titleSmall)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                DocumentsScreen(documents, message, onAdd, onOpen, onRename, onDelete, onSettings, textSize, extraStep)
            } else {
                LibraryScreen(libraryState, libraryController, onSignIn, onAllowEditing)
            }
        }
    }
}
