package org.blaykie.nativeapp

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeMode { System, Light, Dark }
enum class TextSize(val scale: Float) { Small(0.85f), Normal(1f), Large(1.15f) }

data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val textSize: TextSize = TextSize.Normal,
    val customCitationStyles: Boolean = false,
    val styleId: String? = null,
    val languageTag: String? = null,
)

private val styleIdKey = stringPreferencesKey("selected_style_id")
private val languageTagKey = stringPreferencesKey("selected_language_tag")

internal fun MutablePreferences.putStyleId(value: String?) {
    if (value == null) remove(styleIdKey) else this[styleIdKey] = value
}
internal fun MutablePreferences.putLanguageTag(value: String?) {
    if (value == null) remove(languageTagKey) else this[languageTagKey] = value
}
internal fun readSelections(preferences: Preferences): Pair<String?, String?> =
    preferences[styleIdKey] to preferences[languageTagKey]

internal fun parseSettings(theme: String?, textSize: String?, customCitationStyles: Boolean? = null,
                           defaultCustomStyles: Boolean = false, styleId: String? = null, languageTag: String? = null): Settings = Settings(
    theme = ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.System,
    textSize = TextSize.entries.firstOrNull { it.name == textSize } ?: TextSize.Normal,
    customCitationStyles = customCitationStyles ?: defaultCustomStyles,
    styleId = styleId,
    languageTag = languageTag,
)

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val textSizeKey = stringPreferencesKey("text_size")
    private val customCitationStylesKey = booleanPreferencesKey("custom_citation_styles")
    val settings = context.settingsStore.data.map {
        val (styleId, languageTag) = readSelections(it)
        parseSettings(it[themeKey], it[textSizeKey], it[customCitationStylesKey], BuildConfig.CUSTOM_STYLES_DEFAULT, styleId, languageTag)
    }

    suspend fun setTheme(theme: ThemeMode) {
        context.settingsStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setTextSize(size: TextSize) {
        context.settingsStore.edit { it[textSizeKey] = size.name }
    }

    suspend fun setStyleId(value: String?) {
        context.settingsStore.edit { it.putStyleId(value) }
    }

    suspend fun setLanguageTag(value: String?) {
        context.settingsStore.edit { it.putLanguageTag(value) }
    }

    suspend fun setCustomCitationStyles(enabled: Boolean) {
        context.settingsStore.edit { it[customCitationStylesKey] = enabled }
    }
}
