package org.blaykie.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setContent {
            val settings by store.settings.collectAsState(initial = Settings())
            val scope = rememberCoroutineScope()
            val dark = when (settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val density = LocalDensity.current
            val scaledDensity = remember(density, settings.textSize) {
                Density(density.density, density.fontScale * settings.textSize.scale)
            }
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("Settings", style = MaterialTheme.typography.headlineMedium)
                            Text("Theme", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                ThemeMode.entries.forEach { mode ->
                                    Row(
                                        Modifier.fillMaxWidth().selectable(
                                            selected = settings.theme == mode,
                                            role = Role.RadioButton,
                                            onClick = { scope.launch { store.setTheme(mode) } },
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = settings.theme == mode, onClick = null)
                                        Text(mode.name, Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            Text("Text size", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                TextSize.entries.forEach { size ->
                                    Row(
                                        Modifier.fillMaxWidth().selectable(
                                            selected = settings.textSize == size,
                                            role = Role.RadioButton,
                                            onClick = { scope.launch { store.setTextSize(size) } },
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = settings.textSize == size, onClick = null)
                                        Text(size.name, Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
