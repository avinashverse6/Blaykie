package org.blaykie.nativeapp

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.account.AccountController
import org.blaykie.nativeapp.account.AccountGate
import org.blaykie.nativeapp.account.AccountSection
import org.blaykie.nativeapp.account.AccountState
import org.blaykie.nativeapp.account.AccountStore
import org.blaykie.nativeapp.account.AndroidKeyCipher
import org.blaykie.nativeapp.citation.CitationSearchController
import org.blaykie.nativeapp.citation.style.builtInSession
import org.blaykie.nativeapp.citation.style.CitationStyleRow
import org.blaykie.nativeapp.citation.style.CitationStyleSession
import org.blaykie.nativeapp.citation.style.citationUsesCsl
import org.blaykie.nativeapp.citation.style.citationOptionRestriction
import org.blaykie.nativeapp.citation.style.renderCitationPreview
import org.blaykie.nativeapp.citation.style.citationInsertionNote
import org.blaykie.nativeapp.citation.CitationSearchScreen
import org.blaykie.nativeapp.citation.insertionPoint
import org.blaykie.nativeapp.export.DOCX_MIME
import org.blaykie.nativeapp.export.ExportController
import org.blaykie.nativeapp.export.ExportPhase
import org.blaykie.nativeapp.export.beginExport
import org.blaykie.nativeapp.export.ExportScreen
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.NotEditable
import org.blaykie.nativeapp.session.*
import java.io.File

private data class CardData(val index: Int, val kind: String, val text: String)
private data class Loaded(val session: Session, val project: DocxProject, val cards: List<CardData>)
private fun cards(project: DocxProject): List<CardData> = (0 until project.blockCount).mapNotNull { i ->
    val kind = project.blockKind(i); val text = project.blockText(i)
    if (kind == "table" || text.isNotBlank()) CardData(i, kind, text) else null
}
private fun readName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "Document.docx"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // DataStore is asynchronous. A small cached mode lets the initial window
        // match the last selected theme while the authoritative setting loads.
        val launchPreferences = getSharedPreferences("launch_theme", Context.MODE_PRIVATE)
        val launchTheme = ThemeMode.entries.firstOrNull { it.name == launchPreferences.getString("theme", null) } ?: ThemeMode.System
        setTheme(when (launchTheme) {
            ThemeMode.Light -> R.style.Theme_Blaykie_Light
            ThemeMode.Dark -> R.style.Theme_Blaykie_Dark
            ThemeMode.System -> R.style.Theme_Blaykie
        })
        super.onCreate(savedInstanceState)
        CrashLog.install(filesDir)
        val settingsStore = SettingsStore(applicationContext)
        val sessions = SessionStore(filesDir)
        val accountStore = AccountStore(filesDir, AndroidKeyCipher())
        val account = ViewModelProvider(this, AccountController.Factory(accountStore))[AccountController::class.java]
        val citationSearch = ViewModelProvider(this, CitationSearchController.Factory(accountStore))[CitationSearchController::class.java]
        val exportController = ViewModelProvider(this, ExportController.Factory(applicationContext, sessions, accountStore))[ExportController::class.java]
        setContent {
            val settings by settingsStore.settings.collectAsState(initial = null)
            val selectedTheme = settings?.theme ?: launchTheme
            val dark = when (selectedTheme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
            LaunchedEffect(settings?.theme) {
                val mode = settings?.theme ?: return@LaunchedEffect
                if (launchPreferences.getString("theme", null) != mode.name) {
                    // The platform persists this app-local override for the Android 12+
                    // system splash; AUTO clears the override and follows the system.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val nightMode = when (mode) {
                            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                        }
                        getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
                    }
                    launchPreferences.edit().putString("theme", mode.name).commit()
                }
            }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val density = LocalDensity.current
            val textSize = settings?.textSize ?: TextSize.Normal
            val scaled = remember(density, textSize) { Density(density.density, density.fontScale * textSize.scale) }
            CompositionLocalProvider(LocalDensity provides scaled) {
                MaterialTheme(colorScheme = visualColors(dark), typography = visualTypography) {
                    Surface(Modifier.fillMaxSize()) {
                        // Insets are consumed by systemBarsPadding before imePadding, so the
                        // navigation bar is not added a second time when the IME is visible.
                        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                            val loadedSettings = settings
                            if (loadedSettings != null) {
                                AccountGate(this@MainActivity, account) { AppScreens(this@MainActivity, settingsStore, loadedSettings, sessions, account, citationSearch, exportController) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppScreens(context: Context, settingsStore: SettingsStore, settings: Settings, sessions: SessionStore, account: AccountController, citationSearch: CitationSearchController, exportController: ExportController) {
    val accountState by account.state.collectAsState()
    val searchState by citationSearch.state.collectAsState()
    val exportState by exportController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    var screen by remember { mutableStateOf(Screen.valueOf(screenName)) }
    fun navigate(target: Screen) { screen = target; screenName = target.name }
    var session by remember { mutableStateOf<Session?>(null) }
    var project by remember { mutableStateOf<DocxProject?>(null) }
    var blockCards by remember { mutableStateOf<List<CardData>>(emptyList()) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    // The draft text is already persisted in the session; save only its selection in
    // instance state, avoiding a second copy of a potentially very long paragraph.
    var selectionBlock by rememberSaveable { mutableIntStateOf(-1) }
    var selectionStart by rememberSaveable { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf("") }
    var discardAction by rememberSaveable { mutableStateOf<String?>(null) }
    var discardCount by rememberSaveable { mutableIntStateOf(0) }
    var inserting by remember { mutableStateOf(false) }
    val latestCustomStyles by rememberUpdatedState(settings.customCitationStyles)
    var previousCustomStyles by remember { mutableStateOf(settings.customCitationStyles) }

    fun accept(loaded: Loaded) {
        session = loaded.session; project = loaded.project; blockCards = loaded.cards
        val draft = loaded.session.draft
        if (draft != null) {
            val restoreSelection = screen == Screen.EDITOR && selectionBlock == draft.block
            val start = if (restoreSelection) selectionStart.coerceIn(0, draft.text.length) else 0
            val end = if (restoreSelection) selectionEnd.coerceIn(0, draft.text.length) else 0
            field = TextFieldValue(draft.text, selection = TextRange(start, end))
            selectionBlock = draft.block; selectionStart = start; selectionEnd = end
        }
    }
    fun persist(value: Session): Boolean = try {
        sessions.save(value); session = value; true
    } catch (_: Exception) { message = "Could not save the session."; false }
    fun reloadCards() {
        val p = project ?: return
        scope.launch {
            loading = true
            blockCards = withContext(Dispatchers.IO) { cards(p) }
            loading = false
        }
    }
    fun syncEditor(keepDraft: Boolean): Boolean {
        val p = project ?: return false
        val s = session ?: return false
        val draft = s.draft ?: return true
        return try {
            val changed = p.applyTextEdit(draft.block, field.text)
            val updated = s.copy(edits = if (changed) s.edits + EditOperation(draft.block, field.text) else s.edits, draft = if (keepDraft) Draft(draft.block, field.text) else null)
            if (!persist(updated)) { if (changed) p.undo(); false }
            else { reloadCards(); message = ""; true }
        } catch (error: NotEditable) {
            val original = p.blockText(draft.block)
            field = TextFieldValue(original)
            selectionStart = 0; selectionEnd = 0
            persist(s.copy(draft = Draft(draft.block, original)))
            message = error.message?.take(100) ?: "That edit touches protected content."
            false
        }
    }
    fun finishEditor(): Boolean = syncEditor(false)
    fun undoSession(draftBlock: Int? = null) {
        val current = session ?: return
        when (val result = sessions.undo(current, draftBlock)) {
            is UndoResult.Success -> {
                project = result.project; session = result.session
                if (draftBlock != null) {
                    field = TextFieldValue(result.session.draft!!.text)
                    selectionBlock = draftBlock; selectionStart = 0; selectionEnd = 0
                }
                message = ""; reloadCards()
            }
            UndoResult.Empty -> message = "Nothing to undo."
            UndoResult.Failed -> message = "Could not save the session. Undo was not applied."
        }
    }
    fun back() {
        when (screen) {
            Screen.EDITOR -> if (finishEditor()) navigate(Screen.DOCUMENT)
            Screen.DOCUMENT -> navigate(Screen.HOME)
            Screen.EXPORT -> if (exportState.phase in setOf(ExportPhase.IDLE, ExportPhase.DONE)) { exportController.reset(); navigate(Screen.DOCUMENT) } else if (exportState.phase == ExportPhase.PREPARING) exportController.cancelLookup()
            Screen.SETTINGS -> navigate(Screen.HOME)
            Screen.ABOUT, Screen.LAST_CRASH -> navigate(Screen.SETTINGS)
            Screen.HOME -> Unit
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { if (searchState.open) citationSearch.close() else back() }
    LaunchedEffect(accountState.account, searchState.open) {
        if (searchState.open) citationSearch.refreshAccount()
    }
    LaunchedEffect(Unit) {
        val restored = withContext(Dispatchers.IO) {
            when (val r = sessions.read()) {
                is SessionRead.Ready -> Loaded(r.session, r.project, cards(r.project))
                else -> r
            }
        }
        when (restored) {
            is Loaded -> {
                accept(restored)
                if (screen == Screen.EDITOR && restored.session.draft == null) navigate(Screen.DOCUMENT)
            }
            SessionRead.Invalid -> { navigate(Screen.HOME); message = "Could not restore the last session." }
            else -> if (screen == Screen.EDITOR || screen == Screen.DOCUMENT || screen == Screen.EXPORT) navigate(Screen.HOME)
        }
        loading = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true; message = ""
            val opened = withContext(Dispatchers.IO) {
                try {
                    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                    val name = readName(context, uri)
                    if (!name.endsWith(".docx", ignoreCase = true)) return@withContext null to "Please choose a .docx file."
                    val result = sessions.open(uri.toString(), name) { file ->
                        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open source")
                        input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
                    }
                    Loaded(result.session, result.project, cards(result.project)) to ""
                } catch (_: Exception) { null to "This file could not be read as a Word document." }
            }
            if (opened.first == null) message = opened.second
            else { accept(opened.first!!); navigate(Screen.DOCUMENT) }
            loading = false
        }
    }
    fun performDiscardAction(action: String) {
        when (action) {
            "open" -> try { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) } catch (_: Exception) { message = "Could not open the file picker." }
            "end" -> { sessions.end(); session = null; project = null; blockCards = emptyList(); message = "" }
        }
    }
    fun requestDiscardAction(action: String) {
        val current = session; val p = project
        val count = if (current != null && p != null) sessions.unexportedEdits(current, p) else 0
        if (count > 0) { discardCount = count; discardAction = action }
        else performDiscardAction(action)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri -> exportController.destination(uri) }
    LaunchedEffect(exportState.phase) {
        if (exportState.phase == ExportPhase.CHOOSE && exportController.chooserLaunched()) {
            try { exportPicker.launch(exportState.name) } catch (_: Exception) { exportController.destination(null); exportController.message("Could not open the save dialog.") }
        }
    }
    fun sendExport(open: Boolean) {
        val file = exportState.shareFile ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val intent = if (open) Intent(Intent.ACTION_VIEW).setDataAndType(uri, DOCX_MIME) else Intent(Intent.ACTION_SEND).setType(DOCX_MIME).putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "Exported document", uri)
            context.startActivity(if (open) intent else Intent.createChooser(intent, "Share document"))
        } catch (_: ActivityNotFoundException) { exportController.message(if (open) "No app is available to open a .docx." else "Could not open the share sheet.") }
        catch (_: Exception) { exportController.message(if (open) "Could not open the document." else "Could not open the share sheet.") }
    }
    val saveCitationStyle: (String?) -> Boolean = { id -> session?.let { persist(it.copy(debugCitationStyleId = id)) } ?: false }
    val saveCitationLanguage: (String) -> Boolean = { tag -> session?.let { persist(it.copy(debugCitationLanguage = tag, debugCitationLanguageExplicit = true)) } ?: false }
    val useStyleLanguage: () -> Boolean = { session?.let { persist(it.copy(debugCitationLanguageExplicit = false)) } ?: false }
    val saveAutomaticCitationLanguage: (String) -> Boolean = { tag -> session?.let { persist(it.copy(debugCitationLanguage = tag)) } ?: false }
    CitationStyleSession(context, session, settings.customCitationStyles, screen in setOf(Screen.DOCUMENT, Screen.EDITOR, Screen.EXPORT), !loading, saveCitationStyle, saveAutomaticCitationLanguage)
    LaunchedEffect(settings.customCitationStyles, session?.sourceName, session?.debugCitationStyleId) {
        val turnedOff = previousCustomStyles && !settings.customCitationStyles
        previousCustomStyles = settings.customCitationStyles
        val current = session
        if (!settings.customCitationStyles && current != null) {
            val reset = builtInSession(current)
            val changed = reset != current
            if ((!changed || persist(reset)) && (turnedOff || current.debugCitationStyleId != null)) {
                message = "Custom citation styles off; using built-in APA."
            }
        }
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (discardAction != null) AlertDialog(
        onDismissRequest = { discardAction = null },
        title = { Text("Discard $discardCount unexported edits?") },
        confirmButton = { TextButton(onClick = { val action = discardAction; discardAction = null; if (action != null) performDiscardAction(action) }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text("Cancel") } },
    )
    when (screen) {
        Screen.HOME -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Image(painterResource(R.drawable.home_logo), null, Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)))
                    Text("Blaykie", style = MaterialTheme.typography.headlineMedium)
                    Text("Cite smarter. APA 7 in seconds.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = { requestDiscardAction("open") }, modifier = Modifier.fillMaxWidth()) { Text("Open a .docx") }
                    if (session != null && project != null) {
                        OutlinedButton(onClick = { navigate(if (session?.draft != null) Screen.EDITOR else Screen.DOCUMENT) }, modifier = Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Continue"); Text(session?.fileName.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { requestDiscardAction("end") }, modifier = Modifier.fillMaxWidth()) { Text("End session") }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("How it works", style = MaterialTheme.typography.titleSmall)
                            Text("1   Open your Word file", style = MaterialTheme.typography.bodySmall)
                            Text("2   Tap where a citation goes", style = MaterialTheme.typography.bodySmall)
                            Text("3   Pick it from Zotero, then export", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
            SettingsGearButton(onClick = { navigate(Screen.SETTINGS) }, modifier = Modifier.align(Alignment.TopEnd))
        }
        Screen.DOCUMENT -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigate(Screen.HOME) }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(session?.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${project?.citations()?.size ?: 0} citation(s) added", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { undoSession() }) { Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo") }
                Button(onClick = { exportController.reset(); navigate(Screen.EXPORT) }) { Text("Export") }
            }
            if (settings.customCitationStyles) CitationStyleRow(context, session, saveCitationStyle, saveCitationLanguage, useStyleLanguage)
            val legacyCount = project?.legacyCitationCount() ?: 0
            if (legacyCount > 0 || project?.hasBlaykieBibliography() == true) {
                Text("$legacyCount earlier marked citation(s). Citation data may be incomplete; text without citation markers is not protected.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(blockCards, key = { it.index }) { card ->
                    Card(onClick = {
                        if (card.kind == "table" || card.kind == "other") message = "Tables and special blocks cannot be edited."
                        else {
                            field = TextFieldValue(card.text)
                            selectionBlock = card.index; selectionStart = 0; selectionEnd = 0
                            session?.let { if (persist(it.copy(draft = Draft(card.index, card.text)))) { message = ""; navigate(Screen.EDITOR) } }
                        }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            if (card.kind == "table") Text("Table", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(card.text, color = if (card.kind == "table" || card.kind == "other") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                style = if (card.kind.startsWith("heading")) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        Screen.EDITOR -> if (searchState.open) {
            CitationSearchScreen(searchState, citationSearch, paragraph = field.text, onCancel = { citationSearch.close() }, onAddKey = { account.openKey(false) },
                cslSelected = citationUsesCsl(session, settings.customCitationStyles), restriction = citationOptionRestriction(context, session, searchState, settings.customCitationStyles), inserting = inserting, onInsert = {
                val p = project; val s = session; val snapshot = searchState; val chosen = snapshot.selectedItems()
                if (chosen.isEmpty()) citationSearch.message("Select a reference first.")
                else if (p != null && s != null) {
                    fun insert(preview: String?, fallback: Boolean) {
                        val current = session
                        if (current == null || current.sourceName != s.sourceName || project !== p || citationSearch.state.value != snapshot) {
                            if (citationSearch.state.value.open) citationSearch.message("Selection changed. Please try again.")
                            return
                        }
                        try {
                            p.insertCitation(snapshot.block, snapshot.position, snapshot.position, chosen, snapshot.style, snapshot.page.trim(), snapshot.prefix.trim(), preview)
                            val operation = EditOperation(block = snapshot.block, type = "citation", position = snapshot.position, items = chosen,
                                style = snapshot.style, page = snapshot.page.trim(), prefix = snapshot.prefix.trim(), previewText = preview)
                            if (!persist(current.copy(edits = current.edits + operation, draft = null))) p.undo()
                            else {
                                val citation = p.citations().last().text
                                citationSearch.close(); reloadCards(); navigate(Screen.DOCUMENT)
                                message = "Citation added: " + citation.take(50) + when {
                                    else -> citationInsertionNote(fallback, preview != null)
                                }
                            }
                        } catch (error: NotEditable) { citationSearch.message(error.message?.take(100) ?: "This spot is protected.") }
                        catch (_: IllegalArgumentException) { citationSearch.message("Citation could not be inserted.") }
                    }
                    if (!citationUsesCsl(s, settings.customCitationStyles)) insert(null, false)
                    else if (!inserting && citationOptionRestriction(context, s, snapshot, settings.customCitationStyles) == null) {
                        inserting = true
                        scope.launch {
                            try {
                                val existing = p.citations().flatMap { it.items }
                                val preview = renderCitationPreview(context, s, snapshot, existing + chosen, latestCustomStyles)
                                if (latestCustomStyles) insert(preview, preview == null)
                                else if (citationSearch.state.value.open) citationSearch.message("Custom citation styles are off. Please try again.")
                            } finally { inserting = false }
                        }
                    }
                }
            })
        } else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                Text("Paragraph ${session?.draft?.block ?: ""}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
                Button(onClick = { if (finishEditor()) navigate(Screen.DOCUMENT) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Done") }
                TextButton(onClick = {
                    val draft = session?.draft
                    val position = insertionPoint(field.selection.start, field.selection.end, field.text.length)
                    if (draft != null && syncEditor(true)) citationSearch.open(draft.block, position)
                }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Insert citation") }
                TextButton(onClick = {
                    val currentBlock = session?.draft?.block
                    if (currentBlock != null && syncEditor(true)) undoSession(currentBlock)
                }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Undo") }
            }
            ParagraphTextField(value = field, onValueChange = { next ->
                // Normalize only newly inserted line breaks and tabs, not existing document content.
                val old = field.text; val fresh = next.text
                var prefix = 0
                while (prefix < old.length && prefix < fresh.length && old[prefix] == fresh[prefix]) prefix++
                var suffix = 0
                while (suffix < old.length - prefix && suffix < fresh.length - prefix && old[old.lastIndex - suffix] == fresh[fresh.lastIndex - suffix]) suffix++
                val inserted = fresh.substring(prefix, fresh.length - suffix).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                val text = fresh.substring(0, prefix) + inserted + fresh.substring(fresh.length - suffix)
                field = if (fresh == text) next else next.copy(text = text, selection = androidx.compose.ui.text.TextRange(next.selection.start.coerceAtMost(text.length), next.selection.end.coerceAtMost(text.length)), composition = null)
                selectionBlock = session?.draft?.block ?: -1
                selectionStart = field.selection.start; selectionEnd = field.selection.end
                if (text != old) session?.draft?.let { draft -> session?.let { persist(it.copy(draft = draft.copy(text = text))) } }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
        Screen.EXPORT -> project?.let { p ->
            ExportScreen(p, exportState, exportController, onExport = { session?.let { beginExport(context, exportController, it, settings.customCitationStyles) } }, onClose = { back() }, onAddKey = { account.openKey(false) }, onShare = { sendExport(false) }, onOpen = { sendExport(true) })
        }
        Screen.SETTINGS -> SettingsScreen(settings, settingsStore, accountState, onBack = { navigate(Screen.HOME) }, onChangeKey = { account.openKey(false) }, onRemoveKey = { account.remove() }, onAbout = { navigate(Screen.ABOUT) }, onCrash = {
            crashText = try { File(context.filesDir, "crash.log").readText() } catch (_: Exception) { "No crash recorded." }
            navigate(Screen.LAST_CRASH)
        })
        Screen.ABOUT -> AboutScreen(onBack = { navigate(Screen.SETTINGS) })
        Screen.LAST_CRASH -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Last crash", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { navigate(Screen.SETTINGS) }) { Text("Back") }
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Last crash", crashText)) }) { Text("Copy") }
            Text(crashText, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun SettingsGearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bounded viewport around an intrinsically tall text field. Keeping the scroll
 * outside the field lets us reveal the cursor again when only the IME size changes. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ParagraphTextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val outline = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    BoxWithConstraints(modifier.border(if (focused) 2.dp else 1.dp, outline, RoundedCornerShape(4.dp))) {
        val viewportHeight = constraints.maxHeight
        val minTextHeight = (maxHeight - 24.dp).coerceAtLeast(0.dp)
        LaunchedEffect(value.selection, layout, viewportHeight, focused) {
            val result = layout
            if (focused && result != null && result.layoutInput.text.text == value.text) {
                val caret = result.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
                val margin = with(density) { 12.dp.toPx() }
                requester.bringIntoView(caret.copy(top = caret.top - margin, bottom = caret.bottom + margin))
            }
        }
        Box(Modifier.fillMaxSize().imeNestedScroll().verticalScroll(scroll).padding(12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = minTextHeight)
                    .bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout = it },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: Settings, store: SettingsStore, account: AccountState, onBack: () -> Unit, onChangeKey: () -> Unit, onRemoveKey: () -> Unit, onAbout: () -> Unit, onCrash: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            AccountSection(account, onChange = onChangeKey, onRemove = onRemoveKey)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Appearance", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            SettingsRow("Theme", settings.theme.name) { dialog = "theme" }
            SettingsRow("Text size", settings.textSize.name) { dialog = "size" }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Custom citation styles (experimental)", style = MaterialTheme.typography.bodyLarge)
                    Text("Import styles and languages for citations and references.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.customCitationStyles, onCheckedChange = { enabled -> scope.launch { store.setCustomCitationStyles(enabled) } })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("About", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            SettingsRow("About Blaykie", "") { onAbout() }
            SettingsRow("Last crash", "") { onCrash() }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("App version", style = MaterialTheme.typography.bodyLarge)
                val context = androidx.compose.ui.platform.LocalContext.current
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (dialog.isNotEmpty()) AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(if (dialog == "theme") "Theme" else "Text size") }, text = {
        Column(Modifier.selectableGroup()) {
            val labels = if (dialog == "theme") ThemeMode.entries.map { it.name } else TextSize.entries.map { it.name }
            labels.forEach { label ->
                val selected = if (dialog == "theme") settings.theme.name == label else settings.textSize.name == label
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = {
                    if (dialog == "theme") scope.launch { store.setTheme(ThemeMode.valueOf(label)) } else scope.launch { store.setTextSize(TextSize.valueOf(label)) }
                    dialog = ""
                }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null); Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = false, role = Role.Button, onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
