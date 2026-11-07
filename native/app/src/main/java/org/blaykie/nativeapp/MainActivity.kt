package org.blaykie.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        super.onCreate(savedInstanceState)
        CrashLog.install(filesDir)
        val settingsStore = SettingsStore(applicationContext)
        val sessions = SessionStore(filesDir)
        setContent {
            val settings by settingsStore.settings.collectAsState(initial = Settings())
            val dark = when (settings.theme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
            val density = LocalDensity.current
            val scaled = remember(density, settings.textSize) { Density(density.density, density.fontScale * settings.textSize.scale) }
            CompositionLocalProvider(LocalDensity provides scaled) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { AppScreens(this, settingsStore, settings, sessions) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreens(context: Context, settingsStore: SettingsStore, settings: Settings, sessions: SessionStore) {
    val scope = rememberCoroutineScope()
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    var screen by remember { mutableStateOf(Screen.valueOf(screenName)) }
    fun navigate(target: Screen) { screen = target; screenName = target.name }
    var session by remember { mutableStateOf<Session?>(null) }
    var project by remember { mutableStateOf<DocxProject?>(null) }
    var blockCards by remember { mutableStateOf<List<CardData>>(emptyList()) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf("") }

    fun accept(loaded: Loaded) {
        session = loaded.session; project = loaded.project; blockCards = loaded.cards
        val draft = loaded.session.draft
        if (draft != null) field = TextFieldValue(draft.text)
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
    fun finishEditor(): Boolean {
        val p = project ?: return false
        val s = session ?: return false
        val draft = s.draft ?: return true
        return try {
            val changed = p.applyTextEdit(draft.block, field.text)
            val updated = s.copy(edits = if (changed) s.edits + EditOperation(draft.block, field.text) else s.edits, draft = null)
            if (!persist(updated)) { if (changed) p.undo(); false }
            else { reloadCards(); message = ""; true }
        } catch (_: NotEditable) {
            val original = p.blockText(draft.block)
            field = TextFieldValue(original)
            persist(s.copy(draft = Draft(draft.block, original)))
            message = "That edit touches protected content."
            false
        }
    }
    fun back() {
        when (screen) {
            Screen.EDITOR -> if (finishEditor()) navigate(Screen.DOCUMENT)
            Screen.DOCUMENT -> navigate(Screen.HOME)
            Screen.SETTINGS -> navigate(Screen.HOME)
            Screen.LAST_CRASH -> navigate(Screen.SETTINGS)
            Screen.HOME -> Unit
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { back() }
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
            else -> if (screen == Screen.EDITOR || screen == Screen.DOCUMENT) navigate(Screen.HOME)
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
                    require(name.endsWith(".docx", ignoreCase = true))
                    val result = sessions.open(uri.toString(), name) { file ->
                        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open source")
                        input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
                    }
                    Loaded(result.session, result.project, cards(result.project))
                } catch (_: Exception) { null }
            }
            if (opened == null) message = "Could not open this Word document."
            else { accept(opened); navigate(Screen.DOCUMENT) }
            loading = false
        }
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    when (screen) {
        Screen.HOME -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Blaykie", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Text("Open a .docx") }
            if (session != null && project != null) {
                Button(onClick = { navigate(if (session?.draft != null) Screen.EDITOR else Screen.DOCUMENT) }) { Text("Continue") }
                TextButton(onClick = { sessions.end(); session = null; project = null; blockCards = emptyList(); message = "" }) { Text("End session") }
            }
            TextButton(onClick = { navigate(Screen.SETTINGS) }) { Text("Settings") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
        Screen.DOCUMENT -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { navigate(Screen.HOME) }) { Text("Close") }
                TextButton(onClick = {
                    val p = project; val s = session
                    if (p != null && s != null && p.undo()) {
                        persist(s.copy(edits = s.edits.dropLast(1), draft = null)); reloadCards()
                    } else message = "Nothing to undo."
                }) { Text("Undo") }
            }
            Text(session?.fileName.orEmpty(), style = MaterialTheme.typography.titleLarge)
            Text("${session?.edits?.size ?: 0} edit(s)", style = MaterialTheme.typography.bodyMedium)
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(blockCards, key = { it.index }) { card ->
                    Card(onClick = {
                        if (card.kind != "table" && card.kind != "other") {
                            field = TextFieldValue(card.text)
                            session?.let { if (persist(it.copy(draft = Draft(card.index, card.text)))) { message = ""; navigate(Screen.EDITOR) } }
                        }
                    }, enabled = card.kind != "table" && card.kind != "other", modifier = Modifier.fillMaxWidth()) {
                        Text(if (card.kind == "table") "[Table] ${card.text}" else card.text,
                            style = if (card.kind.startsWith("heading")) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        Screen.EDITOR -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paragraph ${session?.draft?.block ?: ""}", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { if (finishEditor()) navigate(Screen.DOCUMENT) }) { Text("Done") }
                TextButton(onClick = {
                    val currentBlock = session?.draft?.block
                    if (currentBlock != null && finishEditor()) {
                        val p = project; val s = session
                        if (p != null && s != null && p.undo()) {
                            val text = p.blockText(currentBlock); field = TextFieldValue(text)
                            persist(s.copy(edits = s.edits.dropLast(1), draft = Draft(currentBlock, text))); reloadCards()
                        } else { session?.let { persist(it.copy(draft = Draft(currentBlock, field.text))) }; message = "Nothing to undo." }
                    }
                }) { Text("Undo") }
            }
            Text("Select text to cut, copy or paste. Changes are applied on Done.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = field, onValueChange = { next ->
                // Normalize only newly inserted line breaks and tabs, not existing document content.
                val old = field.text; val fresh = next.text
                var prefix = 0
                while (prefix < old.length && prefix < fresh.length && old[prefix] == fresh[prefix]) prefix++
                var suffix = 0
                while (suffix < old.length - prefix && suffix < fresh.length - prefix && old[old.lastIndex - suffix] == fresh[fresh.lastIndex - suffix]) suffix++
                val inserted = fresh.substring(prefix, fresh.length - suffix).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                val text = fresh.substring(0, prefix) + inserted + fresh.substring(fresh.length - suffix)
                field = if (fresh == text) next else next.copy(text = text, selection = androidx.compose.ui.text.TextRange(next.selection.start.coerceAtMost(text.length), next.selection.end.coerceAtMost(text.length)), composition = null)
                if (text != old) session?.draft?.let { draft -> session?.let { persist(it.copy(draft = draft.copy(text = text))) } }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
        Screen.SETTINGS -> SettingsScreen(settings, settingsStore, onBack = { navigate(Screen.HOME) }, onCrash = {
            crashText = try { File(context.filesDir, "crash.log").readText() } catch (_: Exception) { "No crash recorded." }
            navigate(Screen.LAST_CRASH)
        })
        Screen.LAST_CRASH -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Last crash", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { navigate(Screen.SETTINGS) }) { Text("Back") }
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Last crash", crashText)) }) { Text("Copy") }
            Text(crashText, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun SettingsScreen(settings: Settings, store: SettingsStore, onBack: () -> Unit, onCrash: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Theme", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(settings.theme == mode, role = Role.RadioButton, onClick = { scope.launch { store.setTheme(mode) } }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.theme == mode, onClick = null); Text(mode.name, Modifier.padding(start = 8.dp))
                }
            }
        }
        Text("Text size", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            TextSize.entries.forEach { size ->
                Row(Modifier.fillMaxWidth().selectable(settings.textSize == size, role = Role.RadioButton, onClick = { scope.launch { store.setTextSize(size) } }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.textSize == size, onClick = null); Text(size.name, Modifier.padding(start = 8.dp))
                }
            }
        }
        TextButton(onClick = onCrash) { Text("Last crash") }
    }
}
