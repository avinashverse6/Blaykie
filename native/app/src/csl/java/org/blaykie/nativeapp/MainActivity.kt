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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
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
import org.blaykie.nativeapp.citation.style.SettingsExtraSection
import org.blaykie.nativeapp.citation.CitationSearchController
import org.blaykie.nativeapp.citation.style.builtInSession
import org.blaykie.nativeapp.citation.style.CitationStyleBanner
import org.blaykie.nativeapp.citation.style.CitationStyleSubtitle
import org.blaykie.nativeapp.citation.style.reconcileSelections
import org.blaykie.nativeapp.citation.style.inventoryRevision
import org.blaykie.nativeapp.csl.StyleRegistry
import org.blaykie.nativeapp.csl.LocaleImportStore
import org.blaykie.nativeapp.csl.styleChoices
import org.blaykie.nativeapp.citation.style.CitationStyleSession
import org.blaykie.nativeapp.citation.style.citationUsesCsl
import org.blaykie.nativeapp.citation.style.citationOptionRestriction
import org.blaykie.nativeapp.citation.style.renderCitationPreview
import org.blaykie.nativeapp.citation.style.citationInsertionNote
import org.blaykie.nativeapp.citation.CitationSearchScreen
import org.blaykie.nativeapp.citation.insertionPoint
import org.blaykie.nativeapp.export.DOCX_MIME
import org.blaykie.nativeapp.render.BibliographyMenu
import org.blaykie.nativeapp.render.MobileDocumentScreen
import org.blaykie.nativeapp.render.MobileDocumentState
import org.blaykie.nativeapp.export.preparePreviewReferences
import org.blaykie.nativeapp.export.ExportController
import org.blaykie.nativeapp.export.ExportPhase
import org.blaykie.nativeapp.export.beginExport
import org.blaykie.nativeapp.export.ExportScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.blaykie.nativeapp.document.DocumentRepository
import org.blaykie.nativeapp.document.DocumentsScreen
import org.blaykie.nativeapp.document.OpenDocument
import org.blaykie.nativeapp.document.StoredDocument
import org.blaykie.nativeapp.document.importDocument
import org.blaykie.nativeapp.document.openDocument
import org.blaykie.nativeapp.document.recordSession
import org.blaykie.nativeapp.docx.DocxProject
import org.blaykie.nativeapp.docx.NotEditable
import org.blaykie.nativeapp.session.*
import java.io.File

private data class Loaded(val session: Session, val project: DocxProject)
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
                                AccountGate(this@MainActivity, account) { AppScreens(this@MainActivity, settingsStore, loadedSettings, account, citationSearch, exportController, accountStore) }
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
private fun AppScreens(context: Context, settingsStore: SettingsStore, settings: Settings, account: AccountController, citationSearch: CitationSearchController, exportController: ExportController, accountStore: AccountStore) {
    val accountState by account.state.collectAsState()
    val searchState by citationSearch.state.collectAsState()
    val exportState by exportController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    var screen by remember { mutableStateOf(Screen.valueOf(screenName)) }
    val documents = remember(context) { DocumentRepository(context) }
    var documentRows by remember { mutableStateOf<List<StoredDocument>>(emptyList()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeStore by remember { mutableStateOf<SessionStore?>(null) }
    var session by remember { mutableStateOf<Session?>(null) }
    var project by remember { mutableStateOf<DocxProject?>(null) }
    var mobile by remember { mutableStateOf(MobileDocumentState()) }
    var selectedCitation by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    // The draft text is already persisted in the session; save only its selection in
    // instance state, avoiding a second copy of a potentially very long paragraph.
    var selectionBlock by rememberSaveable { mutableIntStateOf(-1) }
    var selectionStart by rememberSaveable { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var selectionSync by remember { mutableStateOf(false) }
    var documentStyle by remember { mutableStateOf<String?>(null) }
    var documentLanguage by remember { mutableStateOf("en-US") }
    var documentLanguageExplicit by remember { mutableStateOf(false) }
    var observedSettingsSelection by remember { mutableStateOf(settings.styleId to settings.languageTag) }
    var message by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf("") }
    var inserting by remember { mutableStateOf(false) }
    val latestCustomStyles by rememberUpdatedState(settings.customCitationStyles)

    suspend fun resolveSelections(value: Session, style: String?, language: String, explicit: Boolean, enabled: Boolean): Session {
        if (!enabled) return builtInSession(value)
        val (choices, locales) = withContext(Dispatchers.IO) {
            val styles = runCatching { StyleRegistry.load({ context.assets.open(it) }, java.io.File(context.filesDir, "styles")) }.getOrDefault(emptyList())
            val locales = runCatching { LocaleImportStore(java.io.File(context.filesDir, "locales")).list() }.getOrDefault(emptyList())
            styleChoices(styles) to locales
        }
        val requested = OptionalSelectionState(style, language.takeIf { explicit }, styleInitialized = true)
        return reconcileSelections(requested, value, false, choices, locales).second
    }
    suspend fun accept(loaded: Loaded, opened: OpenDocument) {
        val saved = opened.state
        val style = saved.citationStyle.takeUnless { it == "apa" }
        val resolved = resolveSelections(loaded.session, style, saved.citationLanguage, saved.citationLanguageExplicit, latestCustomStyles)
        if (resolved != loaded.session) opened.store.save(resolved)
        activeStore = opened.store
        activeId = opened.id
        documentStyle = style
        documentLanguage = saved.citationLanguage
        documentLanguageExplicit = saved.citationLanguageExplicit
        session = resolved; project = loaded.project
        val draft = resolved.draft
        if (draft != null) {
            val restoreSelection = screen == Screen.EDITOR && selectionBlock == draft.block
            val start = if (restoreSelection) selectionStart.coerceIn(0, draft.text.length) else 0
            val end = if (restoreSelection) selectionEnd.coerceIn(0, draft.text.length) else 0
            field = TextFieldValue(draft.text, selection = TextRange(start, end))
            selectionBlock = draft.block; selectionStart = start; selectionEnd = end
        }
    }
    fun recordDocumentState(value: Session) {
        val id = activeId ?: return
        try {
            documents.recordSession(id, value, documentStyle ?: "apa", documentLanguage, mobile.position(), documentLanguageExplicit, project?.citations())
        } catch (_: Exception) {
            // The session journal has already been committed; do not roll back the edit.
        }
    }
    fun navigate(target: Screen) {
        if (screen in setOf(Screen.DOCUMENT, Screen.MOBILE_VIEW) && target != screen) session?.let(::recordDocumentState)
        screen = target; screenName = target.name
    }
    val saveOnBackground by rememberUpdatedState({ session?.let(::recordDocumentState); Unit })
    val lifecycle = (context as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveOnBackground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun persist(value: Session): Boolean = try {
        checkNotNull(activeStore).save(value); session = value; recordDocumentState(value); true
    } catch (_: Exception) { message = "Could not save the session."; false }
    fun syncEditor(keepDraft: Boolean): Boolean {
        val p = project ?: return false
        val s = session ?: return false
        val draft = s.draft ?: return true
        return try {
            val changed = p.applyTextEdit(draft.block, field.text)
            val updated = s.copy(edits = if (changed) s.edits + EditOperation(draft.block, field.text) else s.edits, draft = if (keepDraft) Draft(draft.block, field.text) else null)
            if (!persist(updated)) { if (changed) p.undo(); false }
            else { if (changed) mobile.changed(draft.block); message = ""; true }
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
        val store = activeStore ?: return
        if (loading) return
        loading = true
        scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { store.undo(current, draftBlock) }) {
                    is UndoResult.Success -> {
                        project = result.project; session = result.session
                        mobile.changed(current.edits.lastOrNull()?.block)
                        recordDocumentState(result.session)
                        if (draftBlock != null) {
                            field = TextFieldValue(result.session.draft!!.text)
                            selectionBlock = draftBlock; selectionStart = 0; selectionEnd = 0
                        }
                        message = ""
                    }
                    UndoResult.Empty -> message = "Nothing to undo."
                    UndoResult.Failed -> message = "Could not save the session. Undo was not applied."
                }
            } finally { loading = false }
        }
    }
    fun editParagraph(block: Int) {
        val p = project ?: return
        val current = session ?: return
        if (block !in 0 until p.blockCount || p.blockKind(block) in setOf("table", "other")) return
        val draft = current.draft?.takeIf { it.block == block } ?: Draft(block, p.blockText(block))
        if (persist(current.copy(draft = draft))) {
            field = TextFieldValue(draft.text)
            selectionBlock = block; selectionStart = 0; selectionEnd = 0
            mobile.focusParagraph(block)
            message = ""
            navigate(Screen.EDITOR)
        }
    }
    fun removeCitation(block: Int, number: Int) {
        val p = project ?: return
        val current = session ?: return
        try {
            p.removeCitation(block, number)
            val updated = current.copy(edits = current.edits + EditOperation(block, type = "removeCitation", position = number),
                draft = current.draft?.takeUnless { it.block == block })
            if (!persist(updated)) p.undo()
            else { mobile.changed(block); message = "Citation removed." }
        } catch (_: NotEditable) { message = "This citation cannot be removed safely." }
    }
    fun back() {
        when (screen) {
            Screen.EDITOR -> if (finishEditor()) navigate(Screen.DOCUMENT)
            Screen.BIBLIOGRAPHY -> navigate(Screen.DOCUMENT)
            Screen.DOCUMENT, Screen.MOBILE_VIEW -> navigate(Screen.HOME)
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
    suspend fun activate(opened: OpenDocument, restoring: Boolean) {
        val loaded = Loaded(opened.ready.session, opened.ready.project)
        accept(loaded, opened)
        mobile = MobileDocumentState(opened.state.position)
        selectedCitation = null
        session?.let(::recordDocumentState)
        if (!restoring) navigate(Screen.DOCUMENT)
        else if (screen == Screen.EDITOR && session?.draft == null) navigate(Screen.DOCUMENT)
    }
    LaunchedEffect(Unit) {
        try {
            val id = activeId
            if (screen in setOf(Screen.DOCUMENT, Screen.EDITOR, Screen.EXPORT, Screen.BIBLIOGRAPHY, Screen.MOBILE_VIEW)) {
                val opened = withContext(Dispatchers.IO) { id?.let(documents::openDocument) }
                if (opened == null) navigate(Screen.HOME) else activate(opened, restoring = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { navigate(Screen.HOME); message = "Could not restore the document." }
        finally { loading = false }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.HOME) documentRows = withContext(Dispatchers.IO) { documents.list() }
    }
    fun openDocument(id: String) {
        if (loading) return
        loading = true; message = ""
        scope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { documents.openDocument(id) }
                if (opened != null) activate(opened, restoring = false) else message = "Could not open this document."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not open this document." }
            finally { loading = false }
        }
    }
    fun renameDocument(id: String, name: String) {
        scope.launch {
            try {
                documentRows = withContext(Dispatchers.IO) {
                    val current = checkNotNull(documents.load(id))
                    checkNotNull(documents.saveState(id, current.state.copy(displayName = name)))
                    documents.list()
                }
                message = ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not rename this document." }
        }
    }
    fun deleteDocument(id: String) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    if (documents.delete(id)) documents.list() else null
                }
                if (refreshed == null) {
                    message = "Could not delete this document."
                } else {
                    if (activeId == id) {
                        activeId = null; activeStore = null; session = null; project = null
                        mobile = MobileDocumentState(); selectedCitation = null
                        navigate(Screen.HOME)
                    }
                    documentRows = refreshed
                    message = ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not delete this document." }
            finally { loading = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true; message = ""
            try {
                val opened = withContext(Dispatchers.IO) {
                    val name = readName(context, uri)
                    require(name.endsWith(".docx", ignoreCase = true))
                    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open source")
                    input.use { documents.importDocument(uri.toString(), name, it) }
                }
                activate(opened, restoring = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                message = "This file could not be opened as a Word document."
                documentRows = withContext(Dispatchers.IO) { documents.list() }
            } finally { loading = false }
        }
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
    val useBuiltInForSession: (String?) -> Boolean = { id ->
        // Host fallback changes the effective processor, not the document's saved choice.
        session?.let { persist(it.copy(debugCitationStyleId = id)) } ?: false
    }
    val useBuiltInForCurrentSession: () -> Boolean = {
        val previous = documentStyle
        documentStyle = null
        val saved = useBuiltInForSession(null)
        if (!saved) documentStyle = previous
        saved
    }
    val removalRevision by inventoryRevision.collectAsState()
    LaunchedEffect(settings.styleId, settings.languageTag, settings.customCitationStyles, removalRevision) {
        val selection = settings.styleId to settings.languageTag
        val previous = observedSettingsSelection
        observedSettingsSelection = selection
        if (!settings.customCitationStyles) exportController.disableOptionalExport()
        val current = session ?: return@LaunchedEffect
        val style = if (selection.first != previous.first) selection.first else documentStyle
        val languageChanged = selection.second != previous.second
        val language = if (languageChanged) selection.second ?: "en-US" else documentLanguage
        val explicit = if (languageChanged) selection.second != null else documentLanguageExplicit
        selectionSync = true
        try {
            val resolved = resolveSelections(current, style, language, explicit, settings.customCitationStyles)
            val latest = session
            if (latest != null && latest.sourceName == current.sourceName) {
                val updated = latest.copy(debugCitationStyleId = resolved.debugCitationStyleId,
                    debugCitationLanguage = resolved.debugCitationLanguage,
                    debugCitationLanguageExplicit = resolved.debugCitationLanguageExplicit)
                val previousChoice = Triple(documentStyle, documentLanguage, documentLanguageExplicit)
                documentStyle = style; documentLanguage = language; documentLanguageExplicit = explicit
                if (updated == latest) recordDocumentState(latest)
                else if (!persist(updated)) {
                    documentStyle = previousChoice.first
                    documentLanguage = previousChoice.second
                    documentLanguageExplicit = previousChoice.third
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { message = "Could not load the session settings." }
        finally { selectionSync = false }
    }
    val saveAutomaticCitationLanguage: (String) -> Boolean = { tag -> session?.let { persist(it.copy(debugCitationLanguage = tag)) } ?: false }
    CitationStyleSession(context, session, settings.customCitationStyles, screen in setOf(Screen.DOCUMENT, Screen.MOBILE_VIEW, Screen.EDITOR, Screen.EXPORT, Screen.BIBLIOGRAPHY), !loading && !selectionSync, useBuiltInForSession, saveAutomaticCitationLanguage)
    if (loading || selectionSync) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    when (screen) {
        Screen.HOME -> DocumentsScreen(documentRows, message,
            onAdd = {
                try { picker.launch(arrayOf(DOCX_MIME)) }
                catch (_: Exception) { message = "Could not open the file picker." }
            },
            onOpen = ::openDocument, onRename = ::renameDocument, onDelete = ::deleteDocument, onSettings = { navigate(Screen.SETTINGS) },
            textSize = settings.textSize,
            extraStep = if (settings.customCitationStyles) HomeExtraStep("Choose your citation style", R.drawable.ic_style_format) else null,
        )
        Screen.DOCUMENT, Screen.MOBILE_VIEW -> Column(Modifier.fillMaxSize()) {
            val p = project
            val current = session
            val store = activeStore
            DocumentTopBar(current?.fileName.orEmpty(), p?.citations()?.size ?: 0, current?.edits?.size ?: 0,
                onClose = { navigate(Screen.HOME) }, onUndo = { undoSession() },
                onExport = { exportController.reset(); navigate(Screen.EXPORT) },
                menu = {
                    if (current != null) BibliographyMenu(current.bibliography,
                        onChange = { persist(current.copy(bibliography = it)) }, onPreview = { navigate(Screen.BIBLIOGRAPHY) })
                },
                extraSubtitle = { CitationStyleSubtitle(context, session, settings.customCitationStyles) },
            )
            CitationStyleBanner(context, session, settings.customCitationStyles, documentStyle, useBuiltInForCurrentSession)
            val legacyCount = p?.legacyCitationCount() ?: 0
            if (legacyCount > 0 || p?.hasBlaykieBibliography() == true) DocumentTonalBanner {
                Text("$legacyCount earlier marked citation(s). Citation data may be incomplete; text without citation markers is not protected.", style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotEmpty()) DocumentTonalBanner { AppErrorState(message) }
            if (p != null && current != null && store != null) {
                val bibliography = if (!current.bibliography.enabled) null else rememberBibliographyView(
                    p, Triple(current.debugCitationStyleId, current.debugCitationLanguage, settings.customCitationStyles), current.bibliography, revision = mobile.revision, bibliographyOnly = true,
                    prepare = { progress ->
                        preparePreviewReferences(p.copyForBibliographyPreview(), true, accountStore::read, progress)
                    },
                ) { copy, allowMissing -> org.blaykie.nativeapp.export.previewBibliographyXml(context, copy, current, settings.customCitationStyles, allowMissing) }
                MobileDocumentScreen(p, mobile, bibliography, onParagraph = ::editParagraph,
                    onCitation = { block, number -> selectedCitation = block to number })
                selectedCitation?.let { (block, number) ->
                    AlertDialog(onDismissRequest = { selectedCitation = null }, title = { Text("Citation") },
                        confirmButton = { TextButton(onClick = { selectedCitation = null; removeCitation(block, number) }) { Text("Remove") } },
                        dismissButton = {
                            if (current.edits.isNotEmpty()) TextButton(onClick = { selectedCitation = null; undoSession() }) { Text("Undo") }
                            TextButton(onClick = { selectedCitation = null }) { Text("Cancel") }
                        },
                    )
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
                                citationSearch.close(); mobile.changed(snapshot.block); mobile.focusParagraph(snapshot.block); navigate(Screen.DOCUMENT)
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
        } else Column(Modifier.fillMaxSize()) {
            ParagraphEditorTopBar(session?.draft?.block) { if (finishEditor()) navigate(Screen.DOCUMENT) }
            if (message.isNotEmpty()) DocumentTonalBanner { AppErrorState(message) }
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
            }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp))
            ParagraphEditorActions(onInsert = {
                val draft = session?.draft
                val position = insertionPoint(field.selection.start, field.selection.end, field.text.length)
                if (draft != null && syncEditor(true)) citationSearch.open(draft.block, position)
            }, onUndo = {
                val currentBlock = session?.draft?.block
                if (currentBlock != null && syncEditor(true)) undoSession(currentBlock)
            })
        }
        Screen.BIBLIOGRAPHY -> {
            val p = project
            val current = session
            val store = activeStore
            if (p != null && current != null && store != null) BibliographyPreviewScreen(
                p, Triple(current.debugCitationStyleId, current.debugCitationLanguage, settings.customCitationStyles), current.bibliography,
                onClose = { back() },
                prepare = { progress ->
                    preparePreviewReferences(store, current.sourceName, current.bibliography.enabled, accountStore::read, progress)
                },
            ) { copy, allowMissing ->
                org.blaykie.nativeapp.export.previewBibliographyXml(context, copy, current, settings.customCitationStyles, allowMissing)
            }
        }
        Screen.EXPORT -> project?.let { p ->
            ExportScreen(p, exportState, exportController, onExport = { session?.let { beginExport(context, exportController, it, settings.customCitationStyles, checkNotNull(activeStore)) } }, onClose = { back() }, onAddKey = { account.openKey(false) }, onShare = { sendExport(false) }, onOpen = { sendExport(true) })
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
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
        .border(if (focused) 2.dp else 1.dp, outline, RoundedCornerShape(16.dp))) {
        val viewportHeight = constraints.maxHeight
        val minTextHeight = (maxHeight - 32.dp).coerceAtLeast(0.dp)
        LaunchedEffect(value.selection, layout, viewportHeight, focused) {
            val result = layout
            if (focused && result != null && result.layoutInput.text.text == value.text) {
                val caret = result.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
                val margin = with(density) { 12.dp.toPx() }
                requester.bringIntoView(caret.copy(top = caret.top - margin, bottom = caret.bottom + margin))
            }
        }
        Box(Modifier.fillMaxSize().imeNestedScroll().verticalScroll(scroll).padding(16.dp)) {
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
        SettingsTopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSectionTitle("Account")
            AccountSection(account, onChange = onChangeKey, onRemove = onRemoveKey)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsSectionTitle("Appearance")
            SettingsListRow("Theme", settings.theme.name) { dialog = "theme" }
            SettingsListRow("Text size", settings.textSize.name) { dialog = "size" }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Custom citation styles (experimental)", style = MaterialTheme.typography.bodyLarge)
                    Text("Import styles and languages for citations and references.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.customCitationStyles, onCheckedChange = { enabled -> scope.launch { store.setCustomCitationStyles(enabled) } })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsExtraSection(settings, store)
            SettingsSectionTitle("About")
            SettingsListRow("About Blaykie") { onAbout() }
            SettingsListRow("Last crash") { onCrash() }
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
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(selectedOptionModifier(selected)).selectable(selected, role = Role.RadioButton, onClick = {
                    if (dialog == "theme") scope.launch { store.setTheme(ThemeMode.valueOf(label)) } else scope.launch { store.setTextSize(TextSize.valueOf(label)) }
                    dialog = ""
                }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null); Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
}
