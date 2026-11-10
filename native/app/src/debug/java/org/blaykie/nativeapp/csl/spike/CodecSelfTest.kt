package org.blaykie.nativeapp.csl.spike

import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationItem
import org.blaykie.nativeapp.docx.CitationRecordCodec
import org.blaykie.nativeapp.docx.CitationRecords
import org.blaykie.nativeapp.docx.StoredCitation

/** Phone-only diagnostics; never writes a document. */
internal object CodecSelfTest {
    fun run(): List<String> {
        val lines = mutableListOf<String>()
        fun test(name: String, expectError: Boolean = false, operation: () -> Unit) {
            try {
                operation()
                lines += if (expectError) "FAIL $name: expected rejection" else "PASS $name: no exception"
            } catch (t: Throwable) {
                lines += "${if (expectError) "PASS" else "FAIL"} $name: ${t.javaClass.name}"
            }
        }
        test("parser identity") { lines += "Codec parser: ${CitationRecordCodec.parserClassName()}" }
        test("round trip") {
            val r = CitationRecords(citations = listOf(StoredCitation("00000000-0000-0000-0000-000000000001", "BLK_ABC123_1", "parenthetical", "4", "page", "see", listOf(CitationItem("ABC123", "Example", listOf(CitationAuthor("Ada", "Lovelace")), "2020")))))
            check(CitationRecordCodec.read(CitationRecordCodec.write(r)) == r)
        }
        test("malformed", true) { CitationRecordCodec.read("<bad".toByteArray()) }
        test("DOCTYPE", true) { CitationRecordCodec.read("<!DOCTYPE x><x/>".toByteArray()) }
        test("ENTITY", true) { CitationRecordCodec.read("<!ENTITY x 'bad'><x/>".toByteArray()) }
        test("oversized", true) { CitationRecordCodec.read(ByteArray(1_048_577)) }
        test("future version", true) { CitationRecordCodec.read("<blaykie-citations version=\"2\" style=\"apa-built-in\"/>".toByteArray()) }
        return lines
    }
}
