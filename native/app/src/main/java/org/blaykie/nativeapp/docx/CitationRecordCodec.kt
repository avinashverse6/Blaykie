package org.blaykie.nativeapp.docx

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** Round-1 record codec only. Not yet wired into DOCX export or import. */
internal data class StoredCitation(
    val id: String,
    val anchor: String,
    val mode: String,
    val locator: String,
    val locatorLabel: String,
    val prefix: String,
    val items: List<CitationItem>,
)
internal data class CitationRecords(val style: String = "apa-built-in", val citations: List<StoredCitation>)

internal object CitationRecordCodec {
    private const val MAX_BYTES = 1_048_576
    private const val MAX_RECORDS = 1000
    private const val MAX_ITEMS = 50
    private const val MAX_ATTRIBUTE = 2048
    private const val MAX_SNAPSHOT = 65536
    private val key = Regex("[A-Za-z0-9]{1,64}")
    private val anchor = Regex("BLK_[A-Z0-9]{1,12}_[0-9]{1,10}")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** Extra is free-form; retain only the DOI that the current APA formatter uses. */
    private fun safeSnapshot(item: CitationItem): CitationItem {
        val extraDoi = Regex("^\\s*DOI:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(item.extra.trim())?.groupValues?.get(1).orEmpty()
        return item.copy(doi = item.doi.ifBlank { extraDoi }, extra = "")
    }
    private fun attr(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        .replace("\r", "&#xD;").replace("\n", "&#xA;").replace("\t", "&#x9;")
    private fun validXmlText(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (cp != 0x9 && cp != 0xa && cp != 0xd && cp !in 0x20..0xd7ff && cp !in 0xe000..0xfffd && cp !in 0x10000..0x10ffff) return false
            i += Character.charCount(cp)
        }
        return true
    }
    private fun validate(records: CitationRecords) {
        require(records.style == "apa-built-in") { "Unsupported document style" }
        require(records.citations.size <= MAX_RECORDS) { "Too many citations" }
        val ids = mutableSetOf<String>(); val anchors = mutableSetOf<String>()
        for (citation in records.citations) {
            require(runCatching { UUID.fromString(citation.id).toString() == citation.id }.getOrDefault(false)) { "Invalid citation id" }
            require(ids.add(citation.id) && anchor.matches(citation.anchor) && anchors.add(citation.anchor)) { "Duplicate or invalid citation anchor/id" }
            require(citation.mode in setOf("parenthetical", "narrative")) { "Invalid citation mode" }
            require(citation.locatorLabel == "page") { "Invalid locator label" }
            require(citation.locator.length <= MAX_ATTRIBUTE && citation.prefix.length <= MAX_ATTRIBUTE && validXmlText(citation.locator) && validXmlText(citation.prefix)) { "Citation option too long" }
            require(citation.items.isNotEmpty() && citation.items.size <= MAX_ITEMS) { "Invalid number of items" }
            require(citation.items.map { it.key }.toSet().size == citation.items.size) { "Duplicate item key" }
            for (item in citation.items) {
                require(key.matches(item.key)) { "Invalid item key" }
                val snapshot = json.encodeToString(safeSnapshot(item))
                require(validXmlText(snapshot) && snapshot.toByteArray(StandardCharsets.UTF_8).size <= MAX_SNAPSHOT) { "Snapshot too large" }
            }
        }
    }
    fun write(records: CitationRecords): ByteArray {
        validate(records)
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><blaykie-citations version=\"1\" style=\"apa-built-in\">")
            for (c in records.citations) {
                append("<citation id=\"").append(attr(c.id)).append("\" anchor=\"").append(attr(c.anchor))
                append("\" mode=\"").append(attr(c.mode)).append("\" locator=\"").append(attr(c.locator))
                append("\" locator-label=\"").append(attr(c.locatorLabel)).append("\" prefix=\"").append(attr(c.prefix)).append("\">")
                for (item in c.items) {
                    append("<item key=\"").append(attr(item.key)).append("\"><snapshot>")
                    append(attr(json.encodeToString(safeSnapshot(item))))
                    append("</snapshot></item>")
                }
                append("</citation>")
            }
            append("</blaykie-citations>")
        }.toByteArray(StandardCharsets.UTF_8)
        require(xml.size <= MAX_BYTES) { "Citation metadata too large" }
        return xml
    }
    fun read(bytes: ByteArray): CitationRecords {
        require(bytes.size <= MAX_BYTES) { "Citation metadata too large" }
        // Fail closed if a platform XML parser cannot enforce these protections.
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External entities are forbidden") }
        }
        val doc = builder.parse(InputSource(ByteArrayInputStream(bytes)))
        require(doc.doctype == null)
        val root = doc.documentElement
        require(root.tagName == "blaykie-citations" && root.namespaceURI == null) { "Invalid metadata root" }
        checkAttributes(root, setOf("version", "style"))
        require(root.getAttribute("version") == "1") { "Unsupported metadata version" }
        val records = elementChildren(root).map { c ->
            require(c.tagName == "citation" && c.namespaceURI == null)
            checkAttributes(c, setOf("id", "anchor", "mode", "locator", "locator-label", "prefix"))
            val items = elementChildren(c).map { e ->
                require(e.tagName == "item" && e.namespaceURI == null)
                checkAttributes(e, setOf("key"))
                val children = elementChildren(e)
                require(children.size == 1 && children[0].tagName == "snapshot" && children[0].namespaceURI == null)
                val snapshot = children[0]; checkAttributes(snapshot, emptySet())
                require((0 until snapshot.childNodes.length).all { snapshot.childNodes.item(it).nodeType == Node.TEXT_NODE })
                val raw = snapshot.textContent
                require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_SNAPSHOT)
                val item = json.decodeFromString<CitationItem>(raw)
                require(item.key == e.getAttribute("key") && item.extra.isEmpty()) { "Invalid item snapshot" }
                item
            }
            StoredCitation(c.getAttribute("id"), c.getAttribute("anchor"), c.getAttribute("mode"), c.getAttribute("locator"), c.getAttribute("locator-label"), c.getAttribute("prefix"), items)
        }
        return CitationRecords(root.getAttribute("style"), records).also(::validate)
    }
    private fun checkAttributes(element: Element, expected: Set<String>) {
        val names = (0 until element.attributes.length).map { element.attributes.item(it).nodeName }.toSet()
        require(names == expected) { "Unexpected or missing metadata attributes" }
    }
    private fun elementChildren(parent: Element): List<Element> = buildList {
        for (i in 0 until parent.childNodes.length) {
            val node = parent.childNodes.item(i)
            when (node.nodeType) {
                Node.ELEMENT_NODE -> add(node as Element)
                Node.TEXT_NODE -> require(node.textContent.isBlank()) { "Unexpected metadata text" }
                else -> throw IllegalArgumentException("Unexpected metadata node")
            }
        }
    }
}
