package org.blaykie.nativeapp.csl

import java.io.InputStream
import org.blaykie.nativeapp.docx.SafeDocumentXml
import org.w3c.dom.Document

/** CSL keeps its existing limits and uses the shared guarded XML parser. */
internal object CslSafeXml {
    const val MAX_BYTES = 1_048_576
    fun read(input: InputStream): String = SafeDocumentXml.read(input, MAX_BYTES, "CSL")
    fun parse(input: InputStream): Pair<String, Document> = SafeDocumentXml.parse(input, MAX_BYTES, "CSL")
    fun parserClassName(): String = SafeDocumentXml.parserClassName()
}
