package org.blaykie.nativeapp.csl

import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata comes from the style itself; the title includes any edition text. */
internal data class StyleMetadata(val asset: String, val title: String, val id: String, val styleClass: String)

/** Debug-only inventory. This is not yet connected to a processor or a picker. */
internal object StyleRegistry {
    private val assets = listOf("csl/apa.csl")
    private const val CSL_NAMESPACE = "http://purl.org/net/xbiblio/csl"

    fun load(open: (String) -> InputStream): List<StyleMetadata> = assets.map { asset ->
        open(asset).use { read(asset, it) }
    }

    internal fun read(asset: String, input: InputStream): StyleMetadata {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Styles are bundled files, but still never resolve external entities or DTDs.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        require(root.namespaceURI == CSL_NAMESPACE && root.localName == "style") { "Not a CSL style" }
        val infos = root.getElementsByTagNameNS(CSL_NAMESPACE, "info")
        require(infos.length == 1 && infos.item(0).parentNode == root) { "Missing style info" }
        val info = infos.item(0)
        fun directText(name: String): String {
            val matches = (0 until info.childNodes.length).map { info.childNodes.item(it) }
                .filter { it.namespaceURI == CSL_NAMESPACE && it.localName == name }
            require(matches.size == 1) { "Missing or duplicate style $name" }
            return matches.single().textContent.trim().also { require(it.isNotEmpty()) { "Empty style $name" } }
        }
        return StyleMetadata(asset, directText("title"), directText("id"),
            root.getAttribute("class").trim().also { require(it.isNotEmpty()) { "Missing style class" } })
    }
}
