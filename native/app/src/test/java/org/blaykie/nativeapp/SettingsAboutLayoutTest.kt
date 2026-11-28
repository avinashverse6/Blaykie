package org.blaykie.nativeapp

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SettingsAboutLayoutTest {
    private fun root() = listOf(File("../.."), File("."), File("../../.."))
        .first { File(it, "native/app/build.gradle.kts").isFile }

    @Test fun aboutPrivacyAndExistingLegalAssetsRemainAvailable() {
        assertEquals("Your Zotero key is stored encrypted on this phone. Blaykie has no analytics and contacts only api.zotero.org.", AboutInfo.privacy)
        val source = File(root(), "native/app/src/main/java/org/blaykie/nativeapp/AboutScreen.kt").readText()
        assertTrue(source.contains("SettingsTopBar(\"About\", onBack)"))
        assertTrue(source.contains("BlaykieCatLogo(72.dp)"))
        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
        assertTrue(source.contains("licenseRequester.bringIntoView()"))
        assertTrue(source.contains("Text(licenseText, modifier = Modifier.bringIntoViewRequester(licenseRequester).padding(16.dp), style = MaterialTheme.typography.bodyMedium)"))
        assertTrue(source.contains("uri.openUri(AboutInfo.source)"))
    }

    @Test fun settingsVariantsUseGroupedRowsAndSheetPickers() {
        val root = root()
        val component = File(root, "native/app/src/main/java/org/blaykie/nativeapp/SettingsUi.kt").readText()
        assertTrue(component.contains("fillMaxWidth().heightIn(min = 56.dp).clickable"))
        assertTrue(component.contains("ModalBottomSheet("))
        assertTrue(component.contains("RadioButton(selected = selected, onClick = null"))
        assertTrue(component.contains("shape = RoundedCornerShape(22.dp)"))
        for (variant in listOf("csl", "release")) {
            val source = File(root, "native/app/src/$variant/java/org/blaykie/nativeapp/MainActivity.kt").readText()
            assertTrue(source.contains("SettingsTopBar(\"Settings\", onBack)"))
            assertTrue(source.contains("SettingsListRow(\"Theme\", settings.theme.name)"))
            assertTrue(source.contains("SettingsListRow(\"Text size\", settings.textSize.name)"))
            assertTrue(source.contains("SettingsListRow(\"About Blaykie\")"))
            assertTrue(source.contains("SettingsListRow(\"Last crash\")"))
            assertTrue(source.contains("SettingsAppearancePicker(dialog, settings, store)"))
        }
    }
}
