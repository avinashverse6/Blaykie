package org.blaykie.nativeapp.csl

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocaleImportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun xml(tag: String) = """<locale xmlns="http://purl.org/net/xbiblio/csl" xml:lang="$tag"><terms/></locale>"""
    private fun reject(bytes: ByteArray, expected: String) {
        try { LocaleImportStore(File(temp.root, "locales")).import(bytes.inputStream()); fail("accepted") }
        catch (e: LocaleImportFailure) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(expected, true)); assertFalse(e.message.orEmpty().contains('\n')) }
    }
    @Test fun rejectsInvalidLocaleFiles() {
        reject("<html/>".toByteArray(), "not a CSL locale")
        reject("""<locale xmlns="http://purl.org/net/xbiblio/csl"/>""".toByteArray(), "missing language")
        reject(xml("bad tag").toByteArray(), "invalid language")
        reject(ByteArray(LocaleImportStore.MAX_BYTES + 1) { 32 }, "too large")
        reject("<locale>".toByteArray(), "malformed or unsafe")
        reject("<!DOCTYPE locale><locale/>".toByteArray(), "malformed or unsafe")
        reject("<!ENTITY x 'x'><locale/>".toByteArray(), "malformed or unsafe")
        reject(byteArrayOf(0xc3.toByte(), 0x28), "malformed or unsafe")
        reject(("<x>".repeat(130) + "</x>".repeat(130)).toByteArray(), "malformed or unsafe")
        reject(xml("en-US").toByteArray(), "bundled")
    }
    @Test fun hashReplaceLimitAtomicFailureAndRemove() {
        val dir = File(temp.root, "locales"); val store = LocaleImportStore(dir)
        val first = xml("de-DE"); val imported = store.import(first.byteInputStream())
        val name = LocaleImportStore.nameFor("DE-de")
        assertEquals(name, imported.asset); assertTrue(Regex("[0-9a-f]{64}\\.xml").matches(name))
        val file = File(dir, name); assertEquals(first, file.readText())
        val replacement = first.replace("<terms/>", "<terms></terms>")
        store.import(replacement.byteInputStream()); assertEquals(replacement, file.readText())
        val broken = LocaleImportStore(dir) { _, _ -> false }
        try { broken.import(first.byteInputStream()); fail("write accepted") } catch (_: IllegalStateException) { }
        assertEquals(replacement, file.readText()); assertTrue(dir.listFiles()!!.none { it.extension == "tmp" })
        for (i in 1 until 10) store.import(xml("xx-X${i}").byteInputStream())
        reject(xml("fr-FR").toByteArray(), "10 imported")
        store.import(first.byteInputStream()); assertEquals(10, store.list().size)
        assertTrue(store.remove(name)); assertEquals(9, store.list().size)
        try { store.remove("../bad.xml"); fail("unsafe name") } catch (_: IllegalArgumentException) { }
    }
}
