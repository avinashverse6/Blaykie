package org.blaykie.nativeapp

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level guards; actual keyboard, focus and selection behavior still needs a device. */
class ParagraphEditorLayoutTest {
    @Test fun bothVariantsRetainBoundedImeViewportAndSelectionRestoration() {
        val root = listOf(File("../.."), File("."), File("../../.."))
            .first { File(it, "native/app/build.gradle.kts").isFile }
        for (variant in listOf("csl", "release")) {
            val source = File(root, "native/app/src/$variant/java/org/blaykie/nativeapp/MainActivity.kt").readText()
            val editor = source.substringAfter("} else Column(Modifier.fillMaxSize()) {").substringBefore("Screen.EXPORT ->")
            assertTrue("$variant field remains weighted", editor.contains("weight(1f).padding(horizontal = 16.dp)"))
            assertTrue("$variant actions follow field", editor.indexOf("ParagraphEditorActions(") > editor.indexOf("ParagraphTextField("))
            assertTrue("$variant keeps IME inset", source.contains("systemBarsPadding().imePadding()"))
            assertTrue("$variant keeps viewport scroll", source.contains("imeNestedScroll().verticalScroll(scroll)"))
            assertTrue("$variant keeps cursor reveal", source.contains("requester.bringIntoView(caret.copy("))
            assertTrue("$variant reveals cursor when viewport changes", source.contains("LaunchedEffect(value.selection, layout, viewportHeight, focused)"))
            assertTrue("$variant saves selection", source.contains("selectionStart = field.selection.start; selectionEnd = field.selection.end"))
            assertTrue("$variant preserves back action", source.contains("Screen.EDITOR -> if (finishEditor()) navigate(Screen.DOCUMENT)"))
        }
    }
}
