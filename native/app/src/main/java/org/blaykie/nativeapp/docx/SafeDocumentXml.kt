package org.blaykie.nativeapp.docx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource

/** Reject dangerous declarations and excessive input before any XML parser sees the text. */
internal object SafeDocumentXml {
    private const val MAX_DEPTH = 128
    private val forbidden = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)

    fun read(input: InputStream, maxBytes: Int, label: String): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(bytes.size() + count <= maxBytes) { "$label XML too large" }
            bytes.write(buffer, 0, count)
        }
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        return checked(text, label)
    }

    private fun checked(text: String, label: String): String {
        require('\u0000' !in text && !forbidden.containsMatchIn(text)) { "DTD or entity declaration forbidden" }
        var i = 0
        var depth = 0
        var seenRoot = false
        while (i < text.length) {
            if (text[i] != '<') { i++; continue }
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4); require(end >= 0) { "Unterminated comment" }; i = end + 3; continue
            }
            if (text.startsWith("<![CDATA[", i)) {
                require(depth > 0) { "CDATA outside root" }
                val end = text.indexOf("]]>", i + 9); require(end >= 0) { "Unterminated CDATA" }; i = end + 3; continue
            }
            if (text.startsWith("<?xml", i) && i == 0 && !seenRoot) {
                val end = text.indexOf("?>", i + 5); require(end >= 0) { "Unterminated XML declaration" }; i = end + 2; continue
            }
            require(!text.startsWith("<!", i) && !text.startsWith("<?", i)) { "XML declaration forbidden" }
            val closing = text.startsWith("</", i)
            var end = i + 1
            var quote = '\u0000'
            while (end < text.length) {
                val char = text[end]
                if (quote != '\u0000') { if (char == quote) quote = '\u0000' }
                else if (char == '\"' || char == '\'') quote = char
                else if (char == '>') break
                else require(char != '<') { "Invalid tag" }
                end++
            }
            require(end < text.length && quote == '\u0000') { "Unterminated tag" }
            var last = end - 1
            while (last > i && text[last].isWhitespace()) last--
            val selfClosing = text[last] == '/'
            if (closing) { require(!selfClosing && depth > 0) { "Unbalanced XML" }; depth-- }
            else { seenRoot = true; depth++; require(depth <= MAX_DEPTH) { "$label XML nesting too deep" }; if (selfClosing) depth-- }
            i = end + 1
        }
        require(seenRoot && depth == 0) { "Unbalanced XML" }
        return text
    }

    fun parse(input: InputStream, maxBytes: Int, label: String): Pair<String, Document> =
        read(input, maxBytes, label).let { it to parseChecked(it) }

    fun parseSnapshot(text: String, maxCharacters: Int): Document {
        require(text.length <= maxCharacters) { "Document XML too large" }
        return parseChecked(checked(text, "Document"))
    }

    private fun parseChecked(text: String): Document {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entities forbidden") }
        val document = builder.parse(InputSource(StringReader(text)))
        require(document.doctype == null) { "DTD forbidden" }
        return document
    }
    fun parserClassName(): String = DocumentBuilderFactory.newInstance().newDocumentBuilder().javaClass.name
}
