package org.blaykie.nativeapp

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    val styleMessage: String? = null,
    val languageMessage: String? = null,
    /** Last library chosen on the Library tab: 0 = My Library, otherwise a Zotero group id. */
    val libraryGroupId: Long = 0,
)

private val styleIdKey = stringPreferencesKey("selected_style_id")
private val languageTagKey = stringPreferencesKey("selected_language_tag")
private val styleInitializedKey = booleanPreferencesKey("selected_style_initialized")
private val styleMessageKey = stringPreferencesKey("selected_style_message")
private val languageMessageKey = stringPreferencesKey("selected_language_message")
private val styleDismissedKey = stringPreferencesKey("selected_style_message_dismissed_for")
private val languageDismissedKey = stringPreferencesKey("selected_language_message_dismissed_for")

internal data class OptionalSelectionState(
    val styleId: String?, val languageTag: String?, val styleInitialized: Boolean,
    val styleMessage: String? = null, val languageMessage: String? = null,
    val styleMessageDismissedFor: String? = null, val languageMessageDismissedFor: String? = null,
)
internal fun readOptionalSelection(preferences: Preferences) = OptionalSelectionState(
    preferences[styleIdKey], preferences[languageTagKey],
    preferences[styleInitializedKey] == true || preferences[styleIdKey] != null,
    preferences[styleMessageKey], preferences[languageMessageKey],
    preferences[styleDismissedKey], preferences[languageDismissedKey],
)
internal fun MutablePreferences.putOptionalSelection(value: OptionalSelectionState) {
    putStyleId(value.styleId)
    putLanguageTag(value.languageTag)
    this[styleInitializedKey] = value.styleInitialized
    if (value.styleMessage == null) remove(styleMessageKey) else this[styleMessageKey] = value.styleMessage
    if (value.languageMessage == null) remove(languageMessageKey) else this[languageMessageKey] = value.languageMessage
    if (value.styleMessageDismissedFor == null) remove(styleDismissedKey) else this[styleDismissedKey] = value.styleMessageDismissedFor
    if (value.languageMessageDismissedFor == null) remove(languageDismissedKey) else this[languageDismissedKey] = value.languageMessageDismissedFor
}

internal fun MutablePreferences.putStyleId(value: String?) {
    if (value == null) remove(styleIdKey) else this[styleIdKey] = value
}
internal fun MutablePreferences.putLanguageTag(value: String?) {
    if (value == null) remove(languageTagKey) else this[languageTagKey] = value
}
internal fun MutablePreferences.selectStyle(value: String?) {
    putStyleId(value)
    this[styleInitializedKey] = true
    remove(styleMessageKey)
    remove(styleDismissedKey)
}
internal fun MutablePreferences.selectLanguage(value: String?) {
    putLanguageTag(value)
    remove(languageMessageKey)
    remove(languageDismissedKey)
}
internal fun readSelections(preferences: Preferences): Pair<String?, String?> =
    preferences[styleIdKey] to preferences[languageTagKey]

internal fun parseSettings(theme: String?, textSize: String?, customCitationStyles: Boolean? = null,
                           defaultCustomStyles: Boolean = false, styleId: String? = null, languageTag: String? = null,
                           styleMessage: String? = null, languageMessage: String? = null, libraryGroupId: Long = 0): Settings = Settings(
    theme = ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.System,
    textSize = TextSize.entries.firstOrNull { it.name == textSize } ?: TextSize.Normal,
    customCitationStyles = customCitationStyles ?: defaultCustomStyles,
    styleId = styleId,
    languageTag = languageTag,
    styleMessage = styleMessage,
    languageMessage = languageMessage,
    libraryGroupId = libraryGroupId,
)

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val textSizeKey = stringPreferencesKey("text_size")
    private val customCitationStylesKey = booleanPreferencesKey("custom_citation_styles")
    private val libraryGroupIdKey = longPreferencesKey("library_group_id")
    val settings = context.settingsStore.data.map {
        val selection = readOptionalSelection(it)
        parseSettings(it[themeKey], it[textSizeKey], it[customCitationStylesKey], BuildConfig.CUSTOM_STYLES_DEFAULT,
            selection.styleId, selection.languageTag, selection.styleMessage, selection.languageMessage,
            it[libraryGroupIdKey] ?: 0)
    }

    suspend fun setTheme(theme: ThemeMode) {
        context.settingsStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setTextSize(size: TextSize) {
        context.settingsStore.edit { it[textSizeKey] = size.name }
    }

    suspend fun setStyleId(value: String?) {
        context.settingsStore.edit { it.selectStyle(value) }
    }

    suspend fun setLanguageTag(value: String?) {
        context.settingsStore.edit { it.selectLanguage(value) }
    }

    internal suspend fun updateOptionalSelection(transform: (OptionalSelectionState) -> OptionalSelectionState): OptionalSelectionState {
        val result = context.settingsStore.edit { preferences ->
            preferences.putOptionalSelection(transform(readOptionalSelection(preferences)))
        }
        return readOptionalSelection(result)
    }

    suspend fun setLibraryGroupId(id: Long) {
        context.settingsStore.edit { it[libraryGroupIdKey] = id }
    }

    suspend fun setCustomCitationStyles(enabled: Boolean) {
        context.settingsStore.edit { it[customCitationStylesKey] = enabled }
    }
}
