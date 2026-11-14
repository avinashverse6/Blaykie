package org.blaykie.nativeapp.citation.style

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.blaykie.nativeapp.csl.CslWebViewHost
import org.blaykie.nativeapp.csl.CslHostState
import org.blaykie.nativeapp.csl.CitationItemToCsl
import org.blaykie.nativeapp.csl.narrativeAuthor
import org.blaykie.nativeapp.csl.citationRestriction
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.citation.SearchState
import org.blaykie.nativeapp.csl.StyleChoice
import org.blaykie.nativeapp.csl.StyleRegistry
import org.blaykie.nativeapp.csl.StyleImportStore
import org.blaykie.nativeapp.csl.StyleImportFailure
import org.blaykie.nativeapp.csl.LocaleImportFailure
import org.blaykie.nativeapp.csl.LocaleImportStore
import org.blaykie.nativeapp.csl.LocaleMetadata
import org.blaykie.nativeapp.csl.languageAfterRemoval
import org.blaykie.nativeapp.csl.StyleSource
import org.blaykie.nativeapp.csl.styleSelectionId
import org.blaykie.nativeapp.csl.StylePreview
import org.blaykie.nativeapp.csl.StylePreviewData
import org.blaykie.nativeapp.csl.DEPENDENT_STYLE_NOTE
import org.blaykie.nativeapp.csl.PreviewHost
import org.blaykie.nativeapp.csl.PreviewOutcome
import org.blaykie.nativeapp.csl.PreviewFeedback
import org.blaykie.nativeapp.csl.RealPreviewClock
import org.blaykie.nativeapp.csl.runStylePreview
import org.blaykie.nativeapp.csl.previewFeedback
import org.blaykie.nativeapp.csl.styleChoices
import org.blaykie.nativeapp.session.Session
import org.blaykie.nativeapp.citation.processor.CitationProcessor
import org.blaykie.nativeapp.citation.processor.CitationCluster
import org.blaykie.nativeapp.citation.processor.BibliographyResult
import org.blaykie.nativeapp.export.ExportCslStyle
import java.io.File

private data class UiState(val choices: List<StyleChoice> = styleChoices(emptyList()), val preparing: Boolean = false,
    val ready: Boolean = false, val message: String = "", val failedStyle: String? = null,
    val failedIds: Set<String> = emptySet(), val importing: Boolean = false, val failedWarmup: String? = null,
    val preview: StylePreview? = null, val previewing: Boolean = false,
    val locales: List<LocaleMetadata> = emptyList(), val failedLanguage: String? = null)

/** Retained by the activity's ViewModelStore, but holds only application context. */
private class StyleController(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(UiState())
    private var key: String? = null
    private var selected: String? = null
    private var selectedLanguage: String = "en-US"
    private var host: CslWebViewHost? = null
    private var job: Job? = null
    private var previewJob: Job? = null
    private var generation = 0

    fun activate(sessionKey: String, styleId: String?, language: String = "en-US", force: Boolean = false) {
        if (!force && key == sessionKey && selected == styleId && selectedLanguage == language) return
        val fallbackMessage = if (language.equals("en-US", true) && state.value.failedLanguage != null) state.value.message else ""
        val changedSession = key != sessionKey
        if (changedSession) { leave(); key = sessionKey; host = CslWebViewHost(app) }
        else { generation++; host?.cancel(); job?.cancel() }
        selected = styleId
        selectedLanguage = language
        val token = ++generation
        if (styleId == null && !changedSession && state.value.failedStyle != null) {
            state.value = state.value.copy(preparing = false, ready = false)
            return
        }
        state.value = state.value.copy(preparing = styleId != null, ready = false, message = fallbackMessage, failedStyle = null, failedWarmup = null, failedLanguage = null)
        job = scope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    styleChoices(StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles")))
                }
                if (token != generation) return@launch
                val locales = withContext(Dispatchers.IO) { LocaleImportStore(File(app.filesDir, "locales")).list() }
                if (token != generation) return@launch
                state.value = state.value.copy(choices = choices, locales = locales)
                if (!language.equals("en-US", true) && locales.none { it.tag.equals(language, true) }) {
                    state.value = state.value.copy(preparing = false, failedLanguage = language, message = "Language file unavailable; using English (US).")
                    return@launch
                }
                if (styleId == null) return@launch
                val option = choices.singleOrNull { it.id == styleId && it.disabledReason == null }
                    ?: throw IllegalArgumentException("This citation style is unavailable or unsupported.")
                var result = requireNotNull(host).warmUp(requireNotNull(option.id), language)
                if (token != generation) return@launch
                if (result.isFailure && !language.equals("en-US", true)) {
                    result = requireNotNull(host).warmUp(requireNotNull(option.id), "en-US")
                    if (token != generation) return@launch
                    if (result.isSuccess) {
                        state.value = state.value.copy(preparing = false, ready = false, failedLanguage = language,
                            message = "Language could not be loaded; using English (US).")
                        return@launch
                    }
                }
                if (token != generation) return@launch
                if (result.isSuccess) state.value = state.value.copy(preparing = false, ready = true, message = fallbackMessage.ifEmpty { "Style ready" })
                else state.value = state.value.copy(preparing = false, ready = false, failedStyle = styleId, failedWarmup = styleId,
                    failedIds = state.value.failedIds + styleId,
                    message = "Style could not be loaded. Using built-in APA.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) state.value = state.value.copy(preparing = false, ready = false, failedStyle = styleId, failedWarmup = styleId,
                    failedIds = if (styleId == null) state.value.failedIds else state.value.failedIds + styleId,
                    message = "Style could not be loaded. Using built-in APA.")
            }
        }
    }
    suspend fun render(sessionKey: String, styleId: String, language: String, items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>): String? {
        val web = host ?: return null
        val actual = web.state as? CslHostState.Ready ?: return null
        if (key != sessionKey || selected != styleId || selectedLanguage != language || actual.styleId != styleId || !state.value.ready) return null
        if (state.value.choices.none { it.id == styleId && it.disabledReason == null }) return null
        val response = try { web.render(CitationItemToCsl.request(items, context).copy(page = page, prefix = prefix)).getOrThrow() }
        catch (_: Exception) {
            state.value = state.value.copy(ready = false, preparing = false, failedStyle = styleId, message = "Citation style failed.")
            return null
        }
        return if (mode == "narrative") {
            val authors = items.single().authors
            "${narrativeAuthor(response.author, response.andTerm, authors.size, authors.filter { it.group }.map { it.last })} ${response.suppress}"
        } else response.cluster
    }
    fun closePreview() { state.value = state.value.copy(preview = null) }
    fun cancelPreview() { if (state.value.previewing) previewJob?.cancel() }
    fun pickerError() { state.value = state.value.copy(message = "Could not open the file picker.") }
    fun importStyle(uri: Uri) {
        if (state.value.importing) return
        state.value = state.value.copy(importing = true, message = "Importing style…")
        previewJob = scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    (app.contentResolver.openInputStream(uri) ?: error("File could not be opened.")).use { StyleImportStore(File(app.filesDir, "styles"), available = { StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles")) }).import(it) }
                }
                val id = styleSelectionId(imported)
                val choices = withContext(Dispatchers.IO) { styleChoices(StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles"))) }
                state.value = state.value.copy(choices = choices, previewing = true, message = "Preparing style preview…")
                val previewWeb = CslWebViewHost(app)
                val previewHost = object : PreviewHost {
                    override suspend fun render(styleId: String, title: String): StylePreview {
                        val ready = previewWeb.warmUp(styleId)
                        currentCoroutineContext().ensureActive()
                        ready.getOrThrow()
                        val result = previewWeb.bibliography(StylePreviewData.items, StylePreviewData.clusters)
                        currentCoroutineContext().ensureActive()
                        return StylePreviewData.build(title, result.getOrThrow())
                    }
                    override fun close() = previewWeb.destroy()
                }
                val outcome = runStylePreview(previewHost, RealPreviewClock, id, imported.title)
                val feedback = previewFeedback(PreviewFeedback(selected, state.value.failedIds, state.value.message), id, outcome)
                state.value = state.value.copy(importing = false, previewing = false, failedIds = feedback.failed, message = feedback.message,
                    preview = (outcome as? PreviewOutcome.Success)?.preview)
                if (outcome is PreviewOutcome.Success && selected == id) key?.let { activate(it, id, selectedLanguage, force = true) }

            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is StyleImportFailure) e.message.orEmpty() else "Could not import the style file."
                state.value = state.value.copy(importing = false, message = message)
            }
        }
    }
    fun importLocale(uri: Uri) {
        if (state.value.importing) return
        state.value = state.value.copy(importing = true, message = "Importing language…")
        scope.launch {
            try {
                val (imported, locales) = withContext(Dispatchers.IO) {
                    val store = LocaleImportStore(File(app.filesDir, "locales"))
                    val imported = (app.contentResolver.openInputStream(uri) ?: error("Cannot open file")).use { store.import(it) }
                    imported to store.list()
                }
                state.value = state.value.copy(importing = false, locales = locales, message = "Imported language ${imported.tag}.")
                if (selectedLanguage.equals(imported.tag, true)) key?.let { activate(it, selected, selectedLanguage, force = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.value = state.value.copy(importing = false, message = if (e is LocaleImportFailure) e.message.orEmpty() else "Could not import the language file.")
            }
        }
    }
    fun removeLocale(locale: LocaleMetadata) {
        if (state.value.importing) return
        state.value = state.value.copy(importing = true)
        scope.launch {
            try {
                val locales = withContext(Dispatchers.IO) {
                    val store = LocaleImportStore(File(app.filesDir, "locales"))
                    check(store.remove(locale.asset))
                    store.list()
                }
                state.value = state.value.copy(importing = false, locales = locales, message = "Imported language removed.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.value = state.value.copy(importing = false, message = "Could not remove the imported language.") }
        }
    }
    fun removeStyle(choice: StyleChoice) {
        val asset = choice.asset ?: return
        if (choice.source != StyleSource.PRIVATE || state.value.importing) return
        state.value = state.value.copy(importing = true)
        scope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    check(StyleImportStore(File(app.filesDir, "styles")).remove(asset)) { "Could not remove style." }
                    styleChoices(StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles")))
                }
                val selectedChoice = choices.firstOrNull { it.id == selected }
                val unavailable = selected != null && (selectedChoice == null || selectedChoice.disabledReason != null)
                if (unavailable) host?.cancel()
                state.value = state.value.copy(choices = choices, importing = false, failedIds = state.value.failedIds - requireNotNull(choice.id),
                    ready = if (unavailable) false else state.value.ready, failedWarmup = if (unavailable) selected else null,
                    message = if (unavailable) selectedChoice?.disabledReason ?: "Style could not be loaded." else "Imported style removed.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.value = state.value.copy(importing = false, message = "Could not remove the imported style.") }
        }
    }
    fun exportStyle(session: Session): ExportCslStyle? {
        val id = session.debugCitationStyleId ?: return null
        val choice = state.value.choices.singleOrNull { it.id == id && it.disabledReason == null } ?: return null
        val web = host ?: return null
        if (key != session.sourceName || selected != id || selectedLanguage != session.debugCitationLanguage || !state.value.ready || (web.state as? CslHostState.Ready)?.styleId != id) return null
        val processor = object : CitationProcessor {
            override suspend fun citation(items: List<CitationItem>, mode: String, page: String, prefix: String, context: List<CitationItem>): String =
                render(session.sourceName, id, session.debugCitationLanguage, items, mode, page, prefix, context) ?: error("Citation style not ready")
            override suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult {
                check(key == session.sourceName && selected == id && selectedLanguage == session.debugCitationLanguage && state.value.ready && (web.state as? CslHostState.Ready)?.styleId == id) { "Citation style not ready" }
                return web.bibliography(items, clusters).getOrThrow()
            }
        }
        return ExportCslStyle(choice.title, processor)
    }
    fun cancel() {
        generation++; host?.cancel(); job?.cancel(); selected = null
        state.value = state.value.copy(preparing = false, ready = false, message = "", failedStyle = null)
    }
    fun leave() {
        generation++; job?.cancel(); job = null; host?.destroy(); host = null
        key = null; selected = null; selectedLanguage = "en-US"; state.value = UiState()
    }
    override fun onCleared() { leave(); scope.cancel() }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StyleController(context.applicationContext) as T
    }
}

private fun controllerFor(context: Context): StyleController {
    val activity = context as ComponentActivity
    return ViewModelProvider(activity, StyleController.Factory(activity.applicationContext))[StyleController::class.java]
}

internal fun exportCslStyle(context: Context, session: Session): ExportCslStyle? = controllerFor(context).exportStyle(session)

internal fun citationUsesCsl(session: Session?): Boolean = session?.debugCitationStyleId != null
internal fun citationOptionRestriction(context: Context, session: Session?, search: SearchState): String? {
    val styleId = session?.debugCitationStyleId ?: return null
    val choice = controllerFor(context).state.value.choices.firstOrNull { it.id == styleId }
    if (choice?.disabledReason != null) return choice.disabledReason
    return citationRestriction(choice?.originalId ?: styleId, search.style, search.selectedItems().size, search.page, search.prefix)
}
internal suspend fun renderCitationPreview(context: Context, session: Session, search: SearchState, citationContext: List<CitationItem>): String? {
    val id = session.debugCitationStyleId ?: return null
    return controllerFor(context).render(session.sourceName, id, session.debugCitationLanguage, search.selectedItems(), search.style, search.page.trim(), search.prefix.trim(), citationContext)
}

@Composable private fun controller(context: Context): StyleController {
    val activity = context as ComponentActivity
    return remember(activity) { ViewModelProvider(activity, StyleController.Factory(activity.applicationContext))[StyleController::class.java] }
}

@Composable
internal fun CitationStyleSession(context: Context, session: Session?, active: Boolean, loaded: Boolean, onStyleChange: (String?) -> Boolean, onLanguageChange: (String) -> Boolean) {
    val vm = controller(context)
    val state by vm.state.collectAsState()
    LaunchedEffect(loaded, active, session?.sourceName, session?.debugCitationStyleId, session?.debugCitationLanguage) {
        if (loaded) {
            if (active && session != null) vm.activate(session.sourceName, session.debugCitationStyleId, session.debugCitationLanguage)
            else vm.leave()
        }
    }
    LaunchedEffect(state.failedLanguage, session?.debugCitationLanguage, active) {
        if (active && state.failedLanguage != null && state.failedLanguage == session?.debugCitationLanguage) onLanguageChange("en-US")
    }
    LaunchedEffect(state.failedWarmup, session?.debugCitationStyleId, active) {
        if (active && state.failedWarmup != null && state.failedWarmup == session?.debugCitationStyleId) onStyleChange(null)
    }
}

@Composable
internal fun CitationStyleRow(context: Context, session: Session?, onStyleChange: (String?) -> Boolean, onLanguageChange: (String) -> Boolean) {
    val vm = controller(context)
    val state by vm.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<StyleChoice?>(null) }
    var languageExpanded by remember { mutableStateOf(false) }
    var removingLocale by remember { mutableStateOf<LocaleMetadata?>(null) }
    val localePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLocale) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importStyle) }
    val selected = state.choices.firstOrNull { it.id == session?.debugCitationStyleId }?.title ?: "APA 7 (built-in)"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Citation style", style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { expanded = true }) { Text(selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.choices.forEach { choice ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(choice.title + if (choice.source == StyleSource.PRIVATE) " · Imported" else "")
                            if (choice.dependent) Text(DEPENDENT_STYLE_NOTE, style = MaterialTheme.typography.bodySmall)
                            (if (choice.id in state.failedIds) "Failed to load." else choice.disabledReason)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }, trailingIcon = if (choice.source == StyleSource.PRIVATE) ({
                        TextButton(enabled = !state.importing, onClick = { expanded = false; removing = choice }) { Text("Remove") }
                    }) else null, enabled = choice.disabledReason == null && choice.id !in state.failedIds && !state.importing, onClick = {
                        expanded = false
                        onStyleChange(choice.id)
                    })
                }
                DropdownMenuItem(text = { Text("Import style (.csl)") }, enabled = !state.importing, onClick = {
                    expanded = false
                    try { picker.launch(arrayOf("*/*")) } catch (_: Exception) { vm.pickerError() }
                })
            }
        }
        Text("Citation language", style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { languageExpanded = true }) {
                Text(if (session?.debugCitationLanguage == null || session.debugCitationLanguage.equals("en-US", true)) "English (US), bundled" else session.debugCitationLanguage)
            }
            DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                DropdownMenuItem(text = { Text("English (US), bundled") }, enabled = !state.importing, onClick = { languageExpanded = false; onLanguageChange("en-US") })
                state.locales.forEach { locale ->
                    DropdownMenuItem(text = { Text(locale.tag) }, enabled = !state.importing,
                        trailingIcon = { TextButton(enabled = !state.importing, onClick = { languageExpanded = false; removingLocale = locale }) { Text("Remove") } },
                        onClick = { languageExpanded = false; onLanguageChange(locale.tag) })
                }
                DropdownMenuItem(text = { Text("Import language (locale .xml)") }, enabled = !state.importing, onClick = {
                    languageExpanded = false
                    try { localePicker.launch(arrayOf("*/*")) } catch (_: Exception) { vm.pickerError() }
                })
            }
        }
        if (session?.debugCitationStyleId == null) Text("Language applies to CSL styles; built-in APA uses English.", style = MaterialTheme.typography.bodySmall)
        if (state.previewing) Row {
            Text("Preparing style preview", modifier = Modifier.weight(1f))
            TextButton(onClick = vm::cancelPreview) { Text("Cancel preview") }
        }
        if (state.preparing) Row {
            Text("Preparing citation style", modifier = Modifier.weight(1f))
            TextButton(onClick = { if (onStyleChange(null)) vm.cancel() }) { Text("Cancel") }
        }
        if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
    }
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = vm::closePreview, title = { Text("Preview: ${preview.title}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                preview.rows.forEach { row ->
                    Text(row.label, style = MaterialTheme.typography.titleSmall)
                    Text("Parenthetical: ${row.parenthetical}")
                    Text("Narrative: ${row.narrative}")
                    Text("Reference: ${row.bibliography}")
                    Spacer(Modifier.height(8.dp))
                }
            } }, confirmButton = { TextButton(onClick = vm::closePreview) { Text("Done") } })
    }
    removingLocale?.let { locale ->
        AlertDialog(onDismissRequest = { removingLocale = null }, title = { Text("Remove imported language?") },
            text = { Text("Remove ${locale.tag} from this device?") },
            confirmButton = { TextButton(onClick = {
                removingLocale = null
                val selectedLanguage = session?.debugCitationLanguage ?: "en-US"
                val replacement = languageAfterRemoval(selectedLanguage, locale.tag)
                if (replacement == selectedLanguage || onLanguageChange(replacement)) vm.removeLocale(locale)
            }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removingLocale = null }) { Text("Cancel") } })
    }
    removing?.let { choice ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove imported style?") },
            text = { Text("Remove ${choice.title} from this device?") },
            confirmButton = { TextButton(onClick = {
                removing = null
                if (session?.debugCitationStyleId != choice.id || onStyleChange(null)) {
                    if (session?.debugCitationStyleId == choice.id) vm.cancel()
                    vm.removeStyle(choice)
                }
            }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

internal fun citationInsertionNote(fallback: Boolean, rendered: Boolean): String = when {
    fallback -> " — CSL unavailable; used built-in APA."
    rendered -> " — CSL citation."
    else -> ""
}
internal fun citationPreviewNote(): String = "CSL citation is generated on insertion."
