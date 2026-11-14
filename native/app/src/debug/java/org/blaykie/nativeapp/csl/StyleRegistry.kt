package org.blaykie.nativeapp.csl

import java.io.InputStream
import java.io.File
import org.w3c.dom.Element

/** Metadata comes from the style itself; the title includes any edition text. */
internal enum class StyleSource { BUNDLED, PRIVATE }
internal data class StyleMetadata(
    val asset: String, val title: String, val id: String, val styleClass: String?,
    val citationFormat: String?, val dependent: Boolean, val source: StyleSource,
    val parentId: String? = null, val defaultLocale: String? = null,
)

/** Debug-only inventory. This is not yet connected to a processor or a picker. */
internal object StyleRegistry {
    private val assets = listOf("csl/apa.csl")
    private const val CSL_NAMESPACE = "http://purl.org/net/xbiblio/csl"

    /** privateDirectory must be a directory under the application's private storage. */
    fun load(open: (String) -> InputStream, privateDirectory: File? = null): List<StyleMetadata> {
        val bundled = assets.map { asset -> open(asset).use { read(asset, it, StyleSource.BUNDLED) } }
        val privateStyles = if (privateDirectory == null || !privateDirectory.exists()) emptyList() else {
            require(privateDirectory.isDirectory) { "Private style path is not a directory" }
            val dir = privateDirectory.canonicalFile
            requireNotNull(dir.listFiles()) { "Cannot list private styles" }
                .filter { it.name.endsWith(".csl", ignoreCase = true) && it.isFile && it.canonicalFile.parentFile == dir }
                .sortedBy { it.name }
                .map { file -> file.inputStream().use { read(file.name, it, StyleSource.PRIVATE) } }
        }
        return bundled + privateStyles
    }

    internal fun read(asset: String, input: InputStream, source: StyleSource = StyleSource.BUNDLED): StyleMetadata {
        val root = CslSafeXml.parse(input).second.documentElement
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
        val children = (0 until info.childNodes.length).map { info.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.namespaceURI == CSL_NAMESPACE }
        val formats = children.filter { it.localName == "category" && it.hasAttribute("citation-format") }
            .map { it.getAttribute("citation-format").trim() }
        require(formats.size <= 1 && formats.all { it in setOf("author-date", "numeric", "note", "label") }) { "Invalid citation format" }
        val parents = children.filter { it.localName == "link" && it.getAttribute("rel") == "independent-parent" }
        require(parents.size <= 1) { "Invalid parent link" }
        val parentId = parents.singleOrNull()?.getAttribute("href")?.trim()?.also {
            require(it.isNotBlank() && it.length <= 1024 && it.none { c -> c.isISOControl() }) { "Invalid parent link" }
        }
        val dependent = parentId != null
        val defaultLocale = root.getAttribute("default-locale").trim().ifEmpty { null }
        require(defaultLocale == null || Regex("[A-Za-z0-9-]{1,64}").matches(defaultLocale)) { "Invalid default locale" }
        val styleClass = root.getAttribute("class").trim().ifEmpty { null }
        require(styleClass != null || dependent) { "Missing style class" }
        return StyleMetadata(asset, directText("title"), directText("id"), styleClass,
            formats.singleOrNull(), dependent, source, parentId, defaultLocale)
    }
}

/** Prefer the bundled copy when both sources provide the same parent ID. */
internal fun independentParent(style: StyleMetadata, styles: List<StyleMetadata>): StyleMetadata? =
    style.parentId?.let { id -> styles.filter { it.id == id && !it.dependent }.sortedBy { it.source }.firstOrNull() }
internal fun missingParentMessage(id: String): String = "Parent style $id is unavailable; import the parent file first."

internal data class EffectiveStyle(val text: String, val title: String, val defaultLocale: String?)
/** Use the independent parent's unmodified XML; the child's title and locale remain session metadata. */
internal fun effectiveStyle(style: StyleMetadata, styles: List<StyleMetadata>, open: (StyleMetadata) -> InputStream): EffectiveStyle {
    fun readVerified(value: StyleMetadata): String {
        val text = open(value).use { CslSafeXml.read(it) }
        val actual = StyleRegistry.read(value.asset, text.byteInputStream(Charsets.UTF_8), value.source)
        require(actual == value) { "Citation style changed during loading" }
        return text
    }
    if (!style.dependent) return EffectiveStyle(readVerified(style), style.title, null)
    readVerified(style)
    val parent = independentParent(style, styles) ?: throw IllegalArgumentException(missingParentMessage(requireNotNull(style.parentId)))
    require(parent.styleClass == "in-text" && parent.citationFormat == "author-date") { "Numeric and note styles are not supported yet." }
    return EffectiveStyle(readVerified(parent), style.title, style.defaultLocale)
}
