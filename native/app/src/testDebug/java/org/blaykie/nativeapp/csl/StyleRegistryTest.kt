package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class StyleRegistryTest {
    @Test fun readsMetadataFromBundledStyle() {
        val styles = StyleRegistry.load { requireNotNull(javaClass.classLoader?.getResourceAsStream(it)) }
        assertEquals(listOf(StyleMetadata("csl/apa.csl", "APA Style 7th edition", "http://www.zotero.org/styles/apa", "in-text")), styles)
    }

    @Test fun readsOtherMetadataWithoutHardCodedTitles() {
        val xml = """<style xmlns="http://purl.org/net/xbiblio/csl" class="note"><info><title>Example 2nd edition</title><id>example</id></info></style>"""
        val style = StyleRegistry.read("example.csl", xml.byteInputStream())
        assertEquals(StyleMetadata("example.csl", "Example 2nd edition", "example", "note"), style)
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
