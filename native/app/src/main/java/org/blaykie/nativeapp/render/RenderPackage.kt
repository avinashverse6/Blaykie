package org.blaykie.nativeapp.render

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import org.w3c.dom.Element

private const val MAX_PACKAGE_ENTRIES = 10_000
internal const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships"
private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val STRICT_DRAWING = "http://purl.oclc.org/ooxml/drawingml/main"

internal fun <T> readRenderPackage(source: File, read: (ZipFile) -> T): T = ZipFile(source).use { zip ->
    require(zip.size() <= MAX_PACKAGE_ENTRIES) { "Too many DOCX package entries" }
    read(zip)
}

internal fun imageRelationships(root: Element?, zip: ZipFile): Map<String, String> = root?.children().orEmpty()
    .filter { it.localName == "Relationship" && it.namespaceURI == PACKAGE_REL }
    .mapNotNull { rel ->
        if (rel.getAttribute("TargetMode").equals("External", ignoreCase = true)) return@mapNotNull null
        if (rel.getAttribute("Type") !in setOf("$REL/image", "$STRICT_REL/image")) return@mapNotNull null
        val target = mediaPath(rel.getAttribute("Target")) ?: return@mapNotNull null
        val entry = zip.getEntry(target) ?: return@mapNotNull null
        val id = rel.getAttribute("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (entry.isDirectory || entry.size !in 1..MAX_IMAGE_BYTES) null else id to target
    }.toMap()

private fun mediaPath(target: String): String? {
    val uri = runCatching { URI(target) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.path ?: return null
    if (path.any { it == '\\' || it == ':' || it.isISOControl() }) return null
    val entry = if (path.startsWith('/')) path.removePrefix("/") else "word/$path"
    return entry.takeIf { it.startsWith("word/media/") && it.split('/').none { part -> part.isEmpty() || part == ".." || part == "." } }
}

internal fun drawingImages(drawing: Element, relationships: Map<String, String>): List<RenderImage> {
    val nodes = drawing.getElementsByTagNameNS("*", "blip")
    val vml = drawing.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "imagedata")
    val description = (drawing.getElementsByTagNameNS("*", "docPr").item(0) as? Element)?.let {
        it.getAttribute("descr").ifBlank { it.getAttribute("title") }.takeIf(String::isNotBlank)
    }
    fun image(element: Element, attribute: String): RenderImage {
        val id = element.getAttributeNS(REL, attribute).ifEmpty { element.getAttributeNS(STRICT_REL, attribute) }
        return RenderImage(relationships[id], description)
    }
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.namespaceURI == DRAWING || node.namespaceURI == STRICT_DRAWING) add(image(node, "embed"))
        }
        for (i in 0 until vml.length) (vml.item(i) as? Element)?.let { add(image(it, "id")) }
        if (isEmpty()) add(RenderImage(null, description))
    }
}
