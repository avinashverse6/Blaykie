package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class StyleRegistryTest {
    @Test fun readsMetadataFromBundledStyle() {
        val styles = StyleRegistry.load({ requireNotNull(javaClass.classLoader?.getResourceAsStream(it)) })
        assertEquals(listOf(StyleMetadata("csl/apa.csl", "APA Style 7th edition", "http://www.zotero.org/styles/apa", "in-text", "author-date", false, StyleSource.BUNDLED)), styles)
    }

    @Test fun readsOtherMetadataWithoutHardCodedTitles() {
        val xml = """<style xmlns="http://purl.org/net/xbiblio/csl" class="note"><info><title>Example 2nd edition</title><id>example</id></info></style>"""
        val style = StyleRegistry.read("example.csl", xml.byteInputStream())
        assertEquals(StyleMetadata("example.csl", "Example 2nd edition", "example", "note", null, false, StyleSource.BUNDLED), style)
    }

    @Test fun readsPrivateStylesAndDependentMetadata() {
        val directory = java.nio.file.Files.createTempDirectory("styles").toFile()
        try {
            java.io.File(directory, "numeric.csl").writeText("""<style xmlns="http://purl.org/net/xbiblio/csl" class="in-text"><info><title>Numbers</title><id>numeric-id</id><category citation-format="numeric"/></info></style>""")
            java.io.File(directory, "dependent.csl").writeText("""<style xmlns="http://purl.org/net/xbiblio/csl"><info><title>Dependent edition</title><id>dependent-id</id><category citation-format="note"/><link rel="independent-parent" href="https://example.test/parent"/></info></style>""")
            java.io.File(directory, "ignored.txt").writeText("not a style")
            val styles = StyleRegistry.load({ requireNotNull(javaClass.classLoader?.getResourceAsStream(it)) }, directory)
            assertEquals(3, styles.size)
            assertEquals(StyleSource.BUNDLED, styles[0].source)
            assertEquals(StyleMetadata("dependent.csl", "Dependent edition", "dependent-id", null, "note", true, StyleSource.PRIVATE), styles[1])
            assertEquals(StyleMetadata("numeric.csl", "Numbers", "numeric-id", "in-text", "numeric", false, StyleSource.PRIVATE), styles[2])
        } finally { directory.deleteRecursively() }
    }

    @Test fun formatIsReadRatherThanInferredFromClass() {
        val xml = """<style xmlns="http://purl.org/net/xbiblio/csl" class="note"><info><title>Test</title><id>test</id><category citation-format="author-date"/></info></style>"""
        assertEquals("author-date", StyleRegistry.read("test.csl", xml.byteInputStream()).citationFormat)
    }

    @Test fun rejectsMissingMetadataAndDoctype() {
        val invalid = listOf(
            """<style xmlns="http://purl.org/net/xbiblio/csl" class="in-text"><info><title>Example</title></info></style>""",
            """<!DOCTYPE style [<!ENTITY x SYSTEM "file:///etc/passwd">]><style xmlns="http://purl.org/net/xbiblio/csl" class="in-text"><info><title>&x;</title><id>x</id></info></style>"""
        )
        invalid.forEach { xml ->
            try { StyleRegistry.read("invalid.csl", xml.byteInputStream()); fail("Invalid style accepted") }
            catch (expected: Exception) { /* invalid style rejected */ }
        }
    }
}
