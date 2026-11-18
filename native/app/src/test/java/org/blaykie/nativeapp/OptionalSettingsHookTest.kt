package org.blaykie.nativeapp

import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.File
import org.blaykie.nativeapp.citation.style.settingsSectionVisible
import org.junit.Assert.*
import org.junit.Test

class OptionalSettingsHookTest {
    @Test fun hookVisibilityRespectsVariantAndSwitch() {
        assertFalse(settingsSectionVisible(false))
        assertEquals(BuildConfig.CSL_RUNTIME_INCLUDED, settingsSectionVisible(true))
    }

    @Test fun optionalRowsReadSnapshotsAndWriteThroughFakeStore() {
        val preferences = mutablePreferencesOf()
        fun snapshot(): OptionalSettingsBindings {
            val (style, language) = readSelections(preferences)
            return OptionalSettingsBindings(style, language, { preferences.putStyleId(it) }, { preferences.putLanguageTag(it) })
        }
        val initial = snapshot()
        assertNull(initial.styleId)
        assertNull(initial.languageTag)
        initial.chooseStyle("imported-style")
        initial.chooseLanguage("fr-FR")
        assertEquals("imported-style", snapshot().styleId)
        assertEquals("fr-FR", snapshot().languageTag)
        snapshot().chooseStyle(null)
        snapshot().chooseLanguage(null)
        assertNull(snapshot().styleId)
        assertNull(snapshot().languageTag)
    }

    @Test fun absentVariantHookHasNoUiOrCallbacks() {
        val root = listOf(File("../.."), File("."), File("../../.."))
            .first { File(it, "native/app/build.gradle.kts").isFile }
        val stub = File(root, "native/app/src/release/java/org/blaykie/nativeapp/citation/style/SettingsExtraSection.kt").readText()
        assertTrue(stub.contains("internal fun settingsSectionVisible(enabled: Boolean): Boolean = false"))
        assertTrue(stub.contains("internal fun SettingsExtraSection(settings: Settings, store: SettingsStore) = Unit"))
    }
}
