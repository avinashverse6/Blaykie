package org.blaykie.nativeapp.docx

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CitationRecordCodecTest {
    private fun record(id: String = UUID.randomUUID().toString(), anchor: String = "BLK_ABCD2345_1") = StoredCitation(
        id = id, anchor = anchor, mode = "narrative", locator = "11–12", locatorLabel = "page", prefix = "see & compare\nnext",
        items = listOf(CitationItem(key = "ABCD2345", title = "हिन्दी & <Title>", authors = listOf(CitationAuthor("अनिता", "शर्मा")), date = "2020", extra = "DOI: 10.1234/example\nPrivate unstructured text: API-KEY-SECRET"))
    )
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid metadata rejection") } catch (_: Exception) { }
    }
    @Test fun oneCitationRoundTripsWithoutCredentialsOrFreeFormExtra() {
        val input = CitationRecords(citations = listOf(record()))
        val xml = CitationRecordCodec.write(input)
        val text = xml.toString(Charsets.UTF_8)
        assertTrue(text.contains("<blaykie-citations version=\"1\" style=\"apa-built-in\">"))
        assertFalse(text.contains("API-KEY-SECRET"))
        assertFalse(text.contains("Private unstructured text"))
        val restored = CitationRecordCodec.read(xml)
        val expected = input.citations.single()
        val actual = restored.citations.single()
        assertEquals(expected.copy(items = listOf(expected.items.single().copy(doi = "10.1234/example", extra = ""))), actual)
        assertEquals("apa-built-in", restored.style)
    }
    @Test fun multipleRecordsKeepItemAndCitationOrder() {
        val first = record().copy(items = listOf(record().items.single(), record().items.single().copy(key = "SECOND2", title = "Second")))
        val second = record(anchor = "BLK_OTHER2_2")
        val restored = CitationRecordCodec.read(CitationRecordCodec.write(CitationRecords(citations = listOf(first, second))))
        assertEquals(listOf(first.id, second.id), restored.citations.map { it.id })
        assertEquals(listOf("ABCD2345", "SECOND2"), restored.citations.first().items.map { it.key })
    }
    @Test fun rejectsFutureVersionDuplicateIdsAndAnchors() {
        val one = record(); val valid = CitationRecordCodec.write(CitationRecords(citations = listOf(one))).toString(Charsets.UTF_8)
        invalid { CitationRecordCodec.read(valid.replace("version=\"1\" style", "version=\"2\" style").toByteArray()) }
        invalid { CitationRecordCodec.write(CitationRecords(citations = listOf(one, record(anchor = one.anchor)))) }
        invalid { CitationRecordCodec.write(CitationRecords(citations = listOf(one, record(id = one.id, anchor = "BLK_OTHER2_2")))) }
    }
    @Test fun rejectsExternalEntitiesDoctypesMalformedUnexpectedAndOversizedMetadata() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE blaykie-citations [<!ENTITY x SYSTEM "file:///etc/passwd">]><blaykie-citations version="1" style="apa-built-in">&x;</blaykie-citations>"""
        invalid { CitationRecordCodec.read(xxe.toByteArray()) }
        invalid { CitationRecordCodec.read("<blaykie-citations".toByteArray()) }
        invalid { CitationRecordCodec.read("<blaykie-citations version=\"1\" style=\"apa-built-in\"><unknown/></blaykie-citations>".toByteArray()) }
        invalid { CitationRecordCodec.read(ByteArray(1_048_577)) }
    }
    @Test fun preScanRejectsDtdEntityEncodingsAndExcessiveDepth() {
        val cases = listOf(
            "<!DOCTYPE blaykie-citations><blaykie-citations version=\"1\" style=\"apa-built-in\"/>",
            "<!ENTITY x SYSTEM 'file:///etc/passwd'><blaykie-citations version=\"1\" style=\"apa-built-in\"/>",
            "<!doctype blaykie-citations><blaykie-citations version=\"1\" style=\"apa-built-in\"/>",
            "<blaykie-citations version=\"1\" style=\"apa-built-in\"><a><b><c><d/></c></b></a></blaykie-citations>",
            "<blaykie-citations version=\"1\" style=\"apa-built-in\"><![CDATA[unsafe]]></blaykie-citations>"
        )
        cases.forEach { invalid { CitationRecordCodec.read(it.toByteArray()) } }
        invalid { CitationRecordCodec.read(byteArrayOf(0xc3.toByte(), 0x28)) }
        println("Citation codec JVM parser: ${CitationRecordCodec.parserClassName()}")
    }
    @Test fun rejectsMismatchedItemKeyAndUnexpectedCredentialField() {
        val xml = CitationRecordCodec.write(CitationRecords(citations = listOf(record()))).toString(Charsets.UTF_8)
        invalid { CitationRecordCodec.read(xml.replace("<item key=\"ABCD2345\">", "<item key=\"WRONG\">").toByteArray()) }
        invalid { CitationRecordCodec.read(xml.replace("<citation id=", "<citation api-key=\"secret\" id=").toByteArray()) }
    }
}
