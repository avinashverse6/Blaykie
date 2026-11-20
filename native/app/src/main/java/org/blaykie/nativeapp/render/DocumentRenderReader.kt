package org.blaykie.nativeapp.render

import java.io.File
import org.blaykie.nativeapp.docx.SafeDocumentXml
import org.w3c.dom.Element

private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"
private const val MAX_RENDER_XML = 8 * 1024 * 1024
private const val MAX_SUPPORT_XML = 2 * 1024 * 1024

internal class DocumentRenderInput(private val source: File, private val parts: List<String>) {
    fun read(hideBibliography: Boolean = false): DocumentRenderModel {
        require(parts.sumOf { it.length.toLong() } <= MAX_RENDER_XML) { "Document XML too large" }
        return readDocumentRenderModel(source, parts.joinToString(""), hideBibliography)
    }
}

/** Read the in-memory working body and the original package's unchanged styles/numbering. */
internal fun readDocumentRenderModel(source: File, workingXml: String, hideBibliography: Boolean = false): DocumentRenderModel = readRenderPackage(source) { zip ->
    fun part(name: String): Element? = zip.getEntry("word/$name")?.let { entry ->
        zip.getInputStream(entry).use { SafeDocumentXml.parse(it, MAX_SUPPORT_XML, name).second.documentElement }
    }
    val document = SafeDocumentXml.parseSnapshot(workingXml, MAX_RENDER_XML).documentElement
    val relationships = try {
        imageRelationships(part("_rels/document.xml.rels"), zip)
    } catch (_: Exception) {
        emptyMap() // Unreadable image relationships do not hide the document body.
    }
    Renderer(part("styles.xml"), part("numbering.xml"), relationships).read(document, source, hideBibliography)
}

internal fun Element.word(name: String) = localName == name && (namespaceURI == WORD || namespaceURI == STRICT_WORD)
internal fun Element.children(): Sequence<Element> = sequence {
    var child = firstChild
    while (child != null) {
        if (child is Element) yield(child)
        child = child.nextSibling
    }
}
internal fun Element?.get(name: String) = this?.children()?.firstOrNull { it.word(name) }
internal fun Element?.attr(name: String): String? = this?.let {
    (it.getAttributeNS(WORD, name).ifEmpty { it.getAttributeNS(STRICT_WORD, name) }).ifEmpty { null }
}
private fun Element?.value(name: String) = get(name).attr("val")
private fun Element?.enabled(name: String) = get(name)?.let { it.attr("val") !in setOf("0", "false", "off", "none") }

private fun RunFormat.overlay(other: RunFormat) = RunFormat(other.bold ?: bold, other.italic ?: italic,
    other.underline ?: underline, other.font ?: font, other.sizePoints ?: sizePoints, other.rgb ?: rgb)
private fun runFormat(properties: Element?): RunFormat {
    val fonts = properties.get("rFonts")
    return RunFormat(properties.enabled("b"), properties.enabled("i"), properties.enabled("u"),
        fonts.attr("ascii") ?: fonts.attr("hAnsi") ?: fonts.attr("cs"),
        properties.value("sz")?.toIntOrNull()?.takeIf { it > 0 }?.div(2f),
        properties.value("color")?.takeIf { it.length == 6 }?.toIntOrNull(16))
}

private class Renderer(stylePart: Element?, numberingPart: Element?, private val images: Map<String, String>) {
    private val defaults = stylePart.get("docDefaults")
    private val styles = stylePart?.children().orEmpty().filter { it.word("style") }
        .mapNotNull { style -> style.attr("styleId")?.let { it to style } }.toMap()
    private val defaultParagraph = styles.values.firstOrNull { it.attr("type") == "paragraph" && it.attr("default") in setOf("1", "true") }
    private val styleChains = mutableMapOf<String, List<Element>>()
    private val numbering = Numbering(numberingPart)

    private fun chain(id: String?): List<Element> = if (id == null) emptyList() else styleChains.getOrPut(id) {
        val levels = mutableListOf<Element>()
        val seen = mutableSetOf<String>()
        var next: String? = id
        while (next != null && levels.size < 128 && seen.add(next)) {
            val style = styles[next] ?: break
            levels.add(style)
            next = style.value("basedOn")
        }
        levels.asReversed()
    }

    fun read(document: Element, source: File, hideBibliography: Boolean): DocumentRenderModel {
        val body = requireNotNull(document.get("body")) { "Missing document body" }
        val elements = body.children().toList()
        val bibliography = mutableSetOf<Int>()
        if (hideBibliography) elements.forEachIndexed { index, element ->
            if (element.word("bookmarkStart") && element.attr("name") == "BLK_BIBLIOGRAPHY") {
                val id = element.attr("id")
                val end = (index + 1 until elements.size).firstOrNull {
                    elements[it].word("bookmarkEnd") && elements[it].attr("id") == id
                }
                if (end != null) bibliography.addAll(index..end)
            }
        }
        val result = mutableListOf<RenderBlock>()
        val indices = mutableListOf<Int>()
        elements.forEachIndexed { index, element ->
            if (index !in bibliography) renderBlock(element).forEach { block -> result.add(block); indices.add(index) }
        }
        return DocumentRenderModel(result, source, indices, readPageSetup(body.get("sectPr")))
    }

    private fun blocks(container: Element): List<RenderBlock> = container.children().flatMap { renderBlock(it).asSequence() }.toList()

    private fun renderBlock(element: Element): List<RenderBlock> = when {
        element.word("p") -> paragraph(element)
        element.word("tbl") -> listOf(table(element, ::blocks))
        element.word("sdt") -> blocks(element).groupImageFailures()
        element.word("sdtContent") || element.word("customXml") -> blocks(element)
        else -> emptyList()
    }

    private fun paragraph(element: Element): List<RenderBlock> {
        val direct = element.get("pPr")
        val styleId = direct.value("pStyle") ?: defaultParagraph.attr("styleId")
        val inherited = chain(styleId)
        val base = listOfNotNull(defaults.get("pPrDefault").get("pPr")) + inherited.mapNotNull { it.get("pPr") }
        val layers = base + listOfNotNull(direct)
        fun property(name: String) = layers.asReversed().firstNotNullOfOrNull { it.value(name) }
        fun field(group: String, vararg names: String, source: List<Element> = layers): String? =
            source.asReversed().firstNotNullOfOrNull { layer -> names.firstNotNullOfOrNull { layer.get(group).attr(it) } }
        val numId = layers.asReversed().firstNotNullOfOrNull { it.get("numPr").value("numId")?.toIntOrNull() }
        val level = layers.asReversed().firstNotNullOfOrNull { it.get("numPr").value("ilvl")?.toIntOrNull() }
            ?.coerceIn(0, 8) ?: 0
        val item = numId?.takeIf { it > 0 }?.let { numbering.next(it, level) }
        val spacingLayers = base + listOfNotNull(item?.second, direct)
        val heading = property("outlineLvl")?.toIntOrNull()?.let { if (it in 0..8) it + 1 else null }
            ?: if (property("outlineLvl") == null) {
                (listOfNotNull(styleId) + inherited.mapNotNull { it.value("name") }).firstNotNullOfOrNull {
                    HEADING.matchEntire(it)?.groupValues?.get(1)?.toInt()
                }
            } else null
        val baseRun = inherited.fold(runFormat(defaults.get("rPrDefault").get("rPr"))) { acc, style ->
            acc.overlay(runFormat(style.get("rPr")))
        }
        val pieces = mutableListOf<Any>()
        val pageBreaks = mutableListOf<Int>()
        var textLength = 0
        val activeCitations = linkedMapOf<String, Int>()
        fun collect(parent: Element) {
            parent.children().forEach { child ->
                when {
                    child.word("bookmarkStart") -> {
                        val number = child.attr("name")?.let(CITATION_MARKER::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
                        val id = child.attr("id")
                        if (number != null && id != null) activeCitations[id] = number
                    }
                    child.word("bookmarkEnd") -> child.attr("id")?.let(activeCitations::remove)
                    child.isAlternateDrawing() -> pieces.add(horizontalRule(child) ?: alternateDrawingImage(child, images))
                    child.word("r") -> {
                        val props = child.get("rPr")
                        val format = chain(props.value("rStyle")).fold(baseRun) { acc, style ->
                            acc.overlay(runFormat(style.get("rPr")))
                        }.overlay(runFormat(props))
                        val text = StringBuilder()
                        fun flush() {
                            if (text.isNotEmpty()) pieces.add(RenderRun(text.toString(), format, activeCitations.values.lastOrNull()))
                            textLength += text.length
                            text.setLength(0)
                        }
                        child.children().forEach {
                            when {
                                it.word("t") -> text.append(it.textContent)
                                it.word("tab") -> text.append('\t')
                                it.word("br") || it.word("cr") -> {
                                    if (it.word("br") && it.attr("type") == "page") pageBreaks.add(textLength + text.length)
                                    text.append('\n')
                                }
                                it.word("noBreakHyphen") -> text.append('\u2011')
                                it.isAlternateDrawing() -> {
                                    flush()
                                    pieces.add(horizontalRule(it) ?: alternateDrawingImage(it, images))
                                }
                                it.word("drawing") || it.word("pict") || it.word("object") -> {
                                    flush()
                                    val rule = horizontalRule(it)
                                    if (rule != null) pieces.add(rule) else pieces.addAll(drawingImages(it, images))
                                }
                            }
                        }
                        flush()
                    }
                    child.word("hyperlink") || child.word("ins") || child.word("fldSimple") ||
                        child.word("sdtContent") || child.word("sdt") || child.word("smartTag") -> collect(child)
                }
            }
        }
        collect(element)
        fun twips(group: String, vararg names: String): Int = field(group, *names, source = spacingLayers)?.toIntOrNull() ?: 0
        val indentSource = spacingLayers.asReversed().firstOrNull { it.get("ind").let { ind ->
            ind.attr("hanging") != null || ind.attr("firstLine") != null
        } }
        fun border(vararg names: String): RenderBorder? {
            val edge = layers.asReversed().firstNotNullOfOrNull { layer ->
                names.firstNotNullOfOrNull { layer.get("pBdr").get(it) }
            } ?: return null
            if (edge.attr("val") in setOf("nil", "none")) return null
            return RenderBorder(edge.attr("sz")?.toIntOrNull()?.coerceIn(1, 96) ?: 4,
                edge.attr("color")?.takeIf { it.length == 6 }?.toIntOrNull(16),
                edge.attr("space")?.toIntOrNull()?.coerceIn(0, 31) ?: 0)
        }
        val lineSpacing = field("spacing", "line", source = spacingLayers)?.toIntOrNull()
        val lineRule = field("spacing", "lineRule", source = spacingLayers) ?: lineSpacing?.let { "auto" }
        val paragraph = RenderParagraph(emptyList(), when (property("jc")) {
            "center" -> RenderAlignment.Center; "right" -> RenderAlignment.Right; "left" -> RenderAlignment.Left
            "end" -> RenderAlignment.End; "both", "distribute" -> RenderAlignment.Justify; else -> RenderAlignment.Start
        }, twips("spacing", "before"), twips("spacing", "after"),
            lineSpacing, lineRule,
            twips("ind", "start", "left"), indentSource.get("ind").attr("hanging")?.toIntOrNull() ?: 0,
            indentSource.get("ind").attr("firstLine")?.toIntOrNull() ?: 0, heading, item?.first,
            RenderBorders(border("top"), border("bottom"), border("left", "start"), border("right", "end")),
            layers.asReversed().firstNotNullOfOrNull { it.enabled("pageBreakBefore") } == true, pageBreaks)
        if (pieces.none { it is RenderBlock }) return listOf(paragraph.copy(runs = pieces.filterIsInstance<RenderRun>()))
        // Promote drawings to blocks at their run position, preserving text on both sides.
        return buildList {
            val pending = mutableListOf<RenderRun>()
            var hadText = false
            var emittedLength = 0
            fun emitText() {
                if (pending.isEmpty()) return
                val end = emittedLength + pending.sumOf { it.text.length }
                add(paragraph.copy(runs = pending.toList(), list = if (hadText) null else paragraph.list,
                    pageBreakBefore = !hadText && paragraph.pageBreakBefore,
                    pageBreaks = pageBreaks.filter { it in emittedLength until end }.map { it - emittedLength }))
                emittedLength = end
                pending.clear()
                hadText = true
            }
            for (piece in pieces) when (piece) {
                is RenderRun -> pending.add(piece)
                is RenderBlock -> {
                    emitText()
                    if (isEmpty() && paragraph.pageBreakBefore) add(paragraph.copy(pageBreaks = emptyList()))
                    add(piece)
                }
            }
            emitText()
        }.groupImageFailures()

    }

    private companion object {
        val HEADING = Regex("heading\\s*([1-9])", RegexOption.IGNORE_CASE)
        val CITATION_MARKER = Regex("BLK_[A-Z0-9]+_(\\d+)")
    }
}

private class Numbering(private val root: Element?) {
    private fun entries(name: String, id: String): Map<Int, Element> = root?.children().orEmpty()
        .filter { element: Element -> element.word(name) }
        .mapNotNull { element: Element -> element.attr(id)?.toIntOrNull()?.let { key: Int -> key to element } }
        .toMap()
    private val instances = entries("num", "numId")
    private val definitions = entries("abstractNum", "abstractNumId")
    private val counters = mutableMapOf<Int, MutableMap<Int, Int>>()
    private fun instance(id: Int): Element? = instances[id]
    private fun levelOverride(id: Int, level: Int): Element? = instance(id)?.children()?.firstOrNull { element: Element ->
        element.word("lvlOverride") && element.attr("ilvl")?.toIntOrNull() == level
    }
    private fun definition(id: Int, level: Int): Element? = levelOverride(id, level).get("lvl")
        ?: instance(id).value("abstractNumId")?.toIntOrNull()?.let { key: Int -> definitions[key] }
            ?.children()?.firstOrNull { element: Element ->
                element.word("lvl") && element.attr("ilvl")?.toIntOrNull() == level
            }
    private fun start(id: Int, level: Int) = (levelOverride(id, level).value("startOverride")?.toIntOrNull()
        ?: definition(id, level).value("start")?.toIntOrNull() ?: 1).coerceIn(0, 1_000_000)

    fun next(id: Int, level: Int): Pair<RenderListItem, Element?>? {
        val def = definition(id, level) ?: return null
        val format = def.value("numFmt")
        if (format == "none") return null
        val state = counters.getOrPut(id) { mutableMapOf() }
        state.keys.removeAll { it > level && definition(id, it).value("lvlRestart") != "0" }
        state[level] = state[level]?.plus(1) ?: start(id, level)
        val kind = if (format == "bullet") RenderListKind.Bullet else RenderListKind.Numbered
        val pattern = def.value("lvlText")
        val label = if (kind == RenderListKind.Bullet) {
            pattern?.takeIf { it.isNotBlank() && it.none { c -> Character.getType(c) == Character.PRIVATE_USE.toInt() } } ?: "•"
        } else NUMBER_TOKEN.replace(pattern ?: "%${level + 1}.") {
            val index = it.groupValues[1].toInt() - 1
            formatNumber(state[index] ?: start(id, index), definition(id, index).value("numFmt"))
        }
        return RenderListItem(kind, level, label) to def.get("pPr")
    }

    private fun formatNumber(number: Int, format: String?): String {
        if (format == "decimalZero") return number.toString().padStart(2, '0')
        if (format in setOf("lowerLetter", "upperLetter") && number > 0) {
            var remaining = number
            val letters = buildString {
                while (remaining > 0) { remaining--; append('a' + (remaining % 26)); remaining /= 26 }
            }.reversed()
            return if (format == "upperLetter") letters.uppercase() else letters
        }
        if (format in setOf("lowerRoman", "upperRoman") && number in 1..3999) {
            var remaining = number
            val roman = buildString {
                for ((value, symbols) in ROMAN) while (remaining >= value) { append(symbols); remaining -= value }
            }
            return if (format == "lowerRoman") roman.lowercase() else roman
        }
        return number.toString()
    }

    private companion object {
        val NUMBER_TOKEN = Regex("%([1-9])")
        val ROMAN = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    }
}
