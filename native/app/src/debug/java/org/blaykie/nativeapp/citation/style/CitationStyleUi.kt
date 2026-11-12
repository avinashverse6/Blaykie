package org.blaykie.nativeapp.citation.style

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.blaykie.nativeapp.csl.CslWebViewHost
import org.blaykie.nativeapp.csl.StyleChoice
import org.blaykie.nativeapp.csl.StyleRegistry
import org.blaykie.nativeapp.csl.styleChoices
import org.blaykie.nativeapp.session.Session
import java.io.File

private data class UiState(val choices: List<StyleChoice> = styleChoices(emptyList()), val preparing: Boolean = false,
    val ready: Boolean = false, val message: String = "", val failedStyle: String? = null)

/** Retained by the activity's ViewModelStore, but holds only application context. */
private class StyleController(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(UiState())
    private var key: String? = null
    private var selected: String? = null
    private var host: CslWebViewHost? = null
    private var job: Job? = null
    private var generation = 0

    fun activate(sessionKey: String, styleId: String?) {
        if (key == sessionKey && selected == styleId) return
        val changedSession = key != sessionKey
        if (changedSession) { leave(); key = sessionKey; host = CslWebViewHost(app) }
        else { generation++; host?.cancel(); job?.cancel() }
        selected = styleId
        val token = ++generation
        if (styleId == null && !changedSession && state.value.failedStyle != null) {
            state.value = state.value.copy(preparing = false, ready = false)
            return
        }
        state.value = state.value.copy(preparing = styleId != null, ready = false, message = "", failedStyle = null)
        job = scope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    styleChoices(StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles")))
                }
                if (token != generation) return@launch
                state.value = state.value.copy(choices = choices)
                if (styleId == null) return@launch
                val option = choices.singleOrNull { it.id == styleId && it.disabledReason == null }
                    ?: throw IllegalArgumentException("This citation style is unavailable or unsupported.")
                val result = requireNotNull(host).warmUp(requireNotNull(option.id))
                if (token != generation) return@launch
                if (result.isSuccess) state.value = state.value.copy(preparing = false, ready = true, message = "Style ready")
                else state.value = state.value.copy(preparing = false, ready = false, failedStyle = styleId,
                    message = "Could not prepare citation style. Using built-in APA. ${result.exceptionOrNull()?.message.orEmpty().take(160)}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) state.value = state.value.copy(preparing = false, ready = false, failedStyle = styleId,
                    message = "Could not prepare citation style. Using built-in APA. ${e.message.orEmpty().take(160)}")
            }
        }
    }
    fun cancel() {
        generation++; host?.cancel(); job?.cancel(); selected = null
        state.value = state.value.copy(preparing = false, ready = false, message = "", failedStyle = null)
    }
    fun leave() {
        generation++; job?.cancel(); job = null; host?.destroy(); host = null
        key = null; selected = null; state.value = UiState()
    }
    override fun onCleared() { leave(); scope.cancel() }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StyleController(context.applicationContext) as T
    }
}

@Composable private fun controller(context: Context): StyleController {
    val activity = context as ComponentActivity
    return remember(activity) { ViewModelProvider(activity, StyleController.Factory(activity.applicationContext))[StyleController::class.java] }
}

@Composable
internal fun CitationStyleSession(context: Context, session: Session?, active: Boolean, loaded: Boolean, onStyleChange: (String?) -> Boolean) {
    val vm = controller(context)
    val state by vm.state.collectAsState()
    LaunchedEffect(loaded, active, session?.sourceName, session?.debugCitationStyleId) {
        if (loaded) {
            if (active && session != null) vm.activate(session.sourceName, session.debugCitationStyleId)
            else vm.leave()
        }
    }
    LaunchedEffect(state.failedStyle, session?.debugCitationStyleId, active) {
        if (active && state.failedStyle != null && state.failedStyle == session?.debugCitationStyleId) onStyleChange(null)
    }
}

@Composable
internal fun CitationStyleRow(context: Context, session: Session?, onStyleChange: (String?) -> Boolean) {
    val vm = controller(context)
    val state by vm.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val selected = state.choices.firstOrNull { it.id == session?.debugCitationStyleId }?.title ?: "APA 7 (built-in)"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Citation style", style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { expanded = true }) { Text(selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.choices.forEach { choice ->
                    DropdownMenuItem(text = {
                        Column { Text(choice.title); choice.disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }, enabled = choice.disabledReason == null, onClick = {
                        expanded = false
                        onStyleChange(choice.id)
                    })
                }
            }
        }
        if (state.preparing) Row {
            Text("Preparing citation style", modifier = Modifier.weight(1f))
            TextButton(onClick = { if (onStyleChange(null)) vm.cancel() }) { Text("Cancel") }
        }
        if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
    }
}
