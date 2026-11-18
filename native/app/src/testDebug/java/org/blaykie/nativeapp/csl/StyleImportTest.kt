package org.blaykie.nativeapp.csl

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StyleImportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun style(id: String, format: String = "author-date", extra: String = "", kind: String = "in-text", title: String = "Test 2nd edition") =
        """<style xmlns="http://purl.org/net/xbiblio/csl" class="$kind"><info><title>$title</title><id>$id</id><category citation-format="$format"/>$extra</info></style>"""
    private fun store() = StyleImportStore(File(temp.root, "styles"))
    private fun rejected(xml: ByteArray, expected: String) {
        try { store().import(xml.inputStream()); fail("Expected rejection") }
        catch (e: StyleImportFailure) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(expected, true)) }
    }
    @Test fun rejectsInvalidUnsafeTooLargeAndUnsupported() {
        rejected("<html/>".toByteArray(), "not a CSL")
        rejected(ByteArray(StyleImportStore.MAX_BYTES + 1) { 32 }, "too large")
        rejected("<style>".toByteArray(), "malformed or unsafe")
        rejected("<!DOCTYPE style><style/>".toByteArray(), "malformed or unsafe")
        rejected("<!ENTITY x 'x'><style/>".toByteArray(), "malformed or unsafe")
        rejected(byteArrayOf(0xc3.toByte(), 0x28), "malformed or unsafe")
        rejected(("<x>".repeat(130) + "</x>".repeat(130)).toByteArray(), "malformed or unsafe")
        rejected(style("child", extra = """<link rel="independent-parent" href="parent"/>""").toByteArray(), "import the parent file first")
        rejected(style("numeric", format = "numeric").toByteArray(), "not supported yet")
        rejected(style("note", format = "note", kind = "note").toByteArray(), "not supported yet")
        rejected(style("unknown", format = "label").toByteArray(), "author-date")
    }
    @Test fun hashReplaceLimitAtomicFailureAndRemoval() {
        val directory = File(temp.root, "styles")
        val importer = StyleImportStore(directory)
        val id = "http://example.test/../unsafe"
        val first = style(id)
        val imported = importer.import(first.byteInputStream())
        val name = StyleImportStore.nameFor(id)
        assertEquals(name, imported.asset); assertTrue(Regex("[0-9a-f]{64}\\.csl").matches(name))
        val target = File(directory, name); assertEquals(first, target.readText())
        val replacement = style(id, title = "Replacement edition")
        assertEquals("Replacement edition", importer.import(replacement.byteInputStream()).title)
        assertEquals(replacement, target.readText())
        val broken = StyleImportStore(directory, replace = { _, _ -> false })
        try { broken.import(first.byteInputStream()); fail("write accepted") } catch (_: IllegalStateException) { }
        assertEquals(replacement, target.readText()); assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        for (i in 1 until 20) importer.import(style("item-$i").byteInputStream())
        rejected(style("too-many").toByteArray(), "20 imported")
        importer.import(first.byteInputStream()) // replacing at the limit is allowed
        val selected = styleSelectionId(imported)
        assertNull(selectedAfterRemoval(selected, selected)); assertEquals("other", selectedAfterRemoval("other", selected))
        assertTrue(importer.remove(name)); assertFalse(target.exists())
        try { importer.remove("../bad.csl"); fail("unsafe name accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun importedApaAndBundledApaAreDistinctChoices() {
        val bundled = StyleRegistry.load({ requireNotNull(javaClass.classLoader?.getResourceAsStream(it)) }).single()
        val imported = store().import(requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/apa.csl")))
        val choices = styleChoices(listOf(bundled, imported))
        assertEquals(3, choices.size)
        assertNotEquals(choices[1].id, choices[2].id)
        assertNull(choices[1].disabledReason); assertNull(choices[2].disabledReason)
        assertEquals(StyleSource.PRIVATE, choices[2].source)
    }
}
