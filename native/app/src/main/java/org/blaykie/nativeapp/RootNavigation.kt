package org.blaykie.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.account.AccountSummary
import org.blaykie.nativeapp.library.LibraryController

internal enum class RootTab(val label: String) { DOCUMENTS("Documents"), LIBRARY("Library"), SETTINGS("Settings") }
internal val rootTabSaver = Saver<RootTab, String>(save = { it.name }, restore = { rootTabFromName(it) })
internal fun rootTabFromName(name: String?): RootTab = RootTab.entries.firstOrNull { it.name == name } ?: RootTab.DOCUMENTS
internal fun rootTabSelection(current: RootTab, requested: RootTab): Pair<RootTab, Boolean> = requested to (current == requested)

/** Root and Library routes show the bar; transient flows and the IME do not. */
internal fun showRootNavigation(screen: String, keyboardOpen: Boolean, libraryModal: Boolean): Boolean =
    screen in setOf("HOME", "SETTINGS") && !keyboardOpen && !libraryModal

/** Retains the Library back stack and data while the gate or another tab is shown. */
internal class LibraryNavigationModel(private val accountStore: AccountStore) : ViewModel() {
    var controller by mutableStateOf(LibraryController(accountStore))
        private set
    private var knownAccount: AccountSummary? = null
    private var initialized = false

    fun accountChanged(account: AccountSummary?) {
        if (initialized && knownAccount != account) {
            if (knownAccount?.userId != account?.userId) {
                controller.close()
                controller = LibraryController(accountStore)
            } else if (controller.state.value.started) {
                controller.refreshAccount()
            }
        }
        initialized = true
        knownAccount = account
    }
    override fun onCleared() { controller.close() }

    class Factory(private val store: AccountStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryNavigationModel(store) as T
    }
}

private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(draw).build()
private val documentIcon = icon("Documents") {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close()
        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
    }
}
private val libraryIcon = icon("Library") {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 4f); lineTo(9f, 4f); lineTo(9f, 20f); lineTo(4f, 20f); close()
        moveTo(9f, 4f); lineTo(14f, 4f); lineTo(14f, 20f); lineTo(9f, 20f); close()
        moveTo(15f, 5f); lineTo(20f, 6f); lineTo(18f, 20f); lineTo(14f, 19f)
    }
}
private val settingsIcon = icon("Settings") {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 2f); lineTo(12f, 4f); moveTo(12f, 20f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(4f, 12f); moveTo(20f, 12f); lineTo(22f, 12f)
        moveTo(4.9f, 4.9f); lineTo(6.3f, 6.3f); moveTo(17.7f, 17.7f); lineTo(19.1f, 19.1f)
        moveTo(19.1f, 4.9f); lineTo(17.7f, 6.3f); moveTo(6.3f, 17.7f); lineTo(4.9f, 19.1f)
        moveTo(16f, 12f); curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
        curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f); curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
        curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f); close()
    }
}

@Composable
internal fun RootNavigationBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.outline)) {
        NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp,
            modifier = Modifier.heightIn(min = 76.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
            RootTab.entries.forEach { tab ->
                val active = tab == selected
                NavigationBarItem(selected = active, onClick = { onSelect(tab) },
                    icon = { Icon(when (tab) { RootTab.DOCUMENTS -> documentIcon; RootTab.LIBRARY -> libraryIcon; RootTab.SETTINGS -> settingsIcon }, null, Modifier.size(24.dp)) },
                    label = { Text(tab.label, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = colors.onPrimaryContainer,
                        selectedTextColor = colors.onPrimaryContainer, indicatorColor = colors.primaryContainer,
                        unselectedIconColor = colors.onSurfaceVariant, unselectedTextColor = colors.onSurfaceVariant))
            }
        }
    }
}
