package org.blaykie.nativeapp.docx

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

private val tagPattern = Regex("<(/?)([A-Za-z_][\\w:.\\-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>")
private val textPattern = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
private val bookmarkPattern = Regex("w:name=\"BLK_([A-Z0-9]+)_(\\d+)\"")
private const val OBJECT = "\uFFFC"

private data class Element(val tag: String, val start: Int, val end: Int)
private fun children(xml: String, lo: Int, hi: Int): List<Element> {
    val result = mutableListOf<Element>()
    var depth = 0
    var start = 0
    var tag = ""
    for (m in tagPattern.findAll(xml, lo)) {
        if (m.range.first >= hi) break
        val closing = m.groupValues[1].isNotEmpty()
        val selfClosing = m.groupValues[4].isNotEmpty()
        if (closing) {
            depth--
            if (depth == 0) result += Element(tag, start, m.range.last + 1)
        } else if (selfClosing) {
            if (depth == 0) result += Element(m.groupValues[2], m.range.first, m.range.last + 1)
        } else {
            if (depth == 0) { start = m.range.first; tag = m.groupValues[2] }
            depth++
        }
    }
    return result
}
private fun unescape(text: String): String = Regex("&(#x[0-9A-Fa-f]+|#\\d+|amp|lt|gt|quot|apos);").replace(text) {
    val value = it.groupValues[1]
    when (value) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        else -> String(Character.toChars(if (value.startsWith("#x")) value.drop(2).toInt(16) else value.drop(1).toInt()))
    }
}
private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun containerText(xml: String) = textPattern.findAll(xml).joinToString("") { unescape(it.groupValues[1]) }
private fun makeRun(rpr: String, text: String) = if (text.isEmpty()) "" else "<w:r>$rpr<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"

class NotEditable(message: String) : IllegalArgumentException(message)
private data class Segment(val tag: String, val start: Int, val end: Int, val text: String, val kind: String, val rpr: String, val off0: Int, val off1: Int, val depth: Int, val citation: Boolean)
private class Paragraph(private val xml: String) {
    val segments = mutableListOf<Segment>()
    val text: String
    private val innerEnd: Int
    private var finalDepth = 0
    init {
        val open = tagPattern.find(xml) ?: throw IllegalArgumentException("Invalid paragraph")
        innerEnd = if (open.groupValues[4].isNotEmpty()) xml.length else xml.lastIndexOf("</w:p>")
        var offset = 0
        var depth = 0
        val citations = mutableSetOf<String>()
        val metas = setOf("w:pPr", "w:bookmarkStart", "w:bookmarkEnd", "w:proofErr", "w:commentRangeStart", "w:commentRangeEnd", "w:permStart", "w:permEnd", "w:del", "w:moveFrom", "w:moveFromRangeStart", "w:moveFromRangeEnd", "w:moveToRangeStart", "w:moveToRangeEnd", "w:sectPr")
        val all = if (open.groupValues[4].isNotEmpty()) emptyList() else children(xml, open.range.last + 1, innerEnd)
        for (element in all) {
            val fragment = xml.substring(element.start, element.end)
            val citation = citations.isNotEmpty()
            if (element.tag == "w:bookmarkStart") {
                Regex("w:id=\"(\\d+)\" w:name=\"BLK_[A-Z0-9]+_\\d+\"").find(fragment)?.let { citations.add(it.groupValues[1]) }
            } else if (element.tag == "w:bookmarkEnd") {
                Regex("w:id=\"(\\d+)\"").find(fragment)?.let { citations.remove(it.groupValues[1]) }
            }
            var kind = "meta"
            var content = ""
            var rpr = ""
            val beforeDepth = depth
            if (element.tag == "w:r") {
                val head = tagPattern.find(fragment)!!
                val end = fragment.lastIndexOf("</w:r>")
                val kids = if (head.groupValues[4].isNotEmpty() || end < 0) emptyList() else children(fragment, head.range.last + 1, end)
                var simple = true
                for (kid in kids) {
                    val part = fragment.substring(kid.start, kid.end)
                    when (kid.tag) {
                        "w:rPr" -> rpr = part
                        "w:fldChar" -> {
                            when (Regex("w:fldCharType=\"(\\w+)\"").find(part)?.groupValues?.get(1)) { "begin" -> depth++; "end" -> depth-- }
                            simple = false
                        }
                        "w:t", "w:lastRenderedPageBreak" -> Unit
                        else -> simple = false
                    }
                }
                kind = if (simple && depth <= 0) "text" else "locked"
                content = kids.joinToString("") { kid ->
                    val part = fragment.substring(kid.start, kid.end)
                    when (kid.tag) {
                        "w:t" -> containerText(part)
                        "w:tab" -> "\t"
                        "w:br", "w:cr" -> "\n"
                        "w:noBreakHyphen" -> "-"
                        "w:sym", "w:drawing", "w:pict", "w:object", "w:footnoteReference", "w:endnoteReference", "w:commentReference", "mc:AlternateContent" -> OBJECT
                        else -> ""
                    }
                }
            } else if (element.tag !in metas) { kind = "locked"; content = containerText(fragment) }
            segments += Segment(element.tag, element.start, element.end, content, kind, rpr, offset, offset + content.length, beforeDepth, citation)
            offset += content.length
        }
        finalDepth = depth
        text = segments.joinToString("") { it.text }
    }

    fun format(start: Int, end: Int): String = (segments.firstOrNull { it.kind == "text" && it.text.isNotEmpty() && it.off0 < start && start <= it.off1 }
        ?: segments.firstOrNull { it.kind == "text" && it.text.isNotEmpty() && it.off0 >= end }
        ?: segments.firstOrNull { it.kind == "text" })?.rpr ?: ""

    fun replace(start: Int, end: Int, replacement: String): String {
        if (start < 0 || end < start || end > text.length) throw NotEditable("Position is outside the paragraph.")
        if (start == end) {
            val seg = segments.firstOrNull { it.kind != "meta" && it.off0 < start && start < it.off1 }
            if (seg != null) {
                if (seg.kind != "text" || seg.citation) throw NotEditable("This spot is protected.")
                return xml.substring(0, seg.start) + makeRun(seg.rpr, seg.text.substring(0, start - seg.off0)) + replacement + makeRun(seg.rpr, seg.text.substring(start - seg.off0)) + xml.substring(seg.end)
            }
            val first = if (segments.firstOrNull()?.tag == "w:pPr") 1 else 0
            val candidate = (first..segments.size).firstOrNull { index ->
                (if (index < segments.size) segments[index].off0 else text.length) == start &&
                    (if (index < segments.size) segments[index].depth else finalDepth) == 0
            } ?: throw NotEditable("This spot is protected.")
            var index = candidate
            while (index < segments.size && segments[index].tag in setOf("w:bookmarkEnd", "w:proofErr", "w:commentRangeEnd") && segments[index].off0 == start) index++
            val pos = if (index < segments.size) segments[index].start else innerEnd
            if (innerEnd == xml.length) {
                return xml.substring(0, xml.length - 2) + ">" + replacement + "</w:p>"
            }
            return xml.substring(0, pos) + replacement + xml.substring(pos)
        }
        val touched = segments.withIndex().filter { it.value.kind != "meta" && it.value.text.isNotEmpty() && it.value.off1 > start && it.value.off0 < end }
        if (touched.isEmpty() || touched.any { it.value.kind != "text" || (it.value.citation && !(start <= it.value.off0 && end >= it.value.off1)) }) throw NotEditable("The selection is protected.")
        val first = touched.first(); val last = touched.last()
        if ((first.index..last.index).any { segments[it].depth != 0 }) throw NotEditable("The selection is inside a field.")
        val left = if (start > first.value.off0) makeRun(first.value.rpr, first.value.text.substring(0, start - first.value.off0)) else ""
        val right = if (end < last.value.off1) makeRun(last.value.rpr, last.value.text.substring(end - last.value.off0)) else ""
        val metas = segments.drop(first.index + 1).take((last.index - first.index - 1).coerceAtLeast(0)).filter { it.kind != "text" }.joinToString("") { xml.substring(it.start, it.end) }
        return xml.substring(0, first.value.start) + left + replacement + right + metas + xml.substring(last.value.end)
    }
}

/** The non-junk SequenceMatcher algorithm used by Python's apply_text_edit. */
private data class Match(val a: Int, val b: Int, val size: Int)
private data class TextChange(val start: Int, val end: Int, val text: String)
private fun textChanges(old: String, updated: String): List<TextChange> {
    val positions = mutableMapOf<Char, MutableList<Int>>()
    updated.forEachIndexed { index, char -> positions.getOrPut(char) { mutableListOf() }.add(index) }
    data class Range(val a0: Int, val a1: Int, val b0: Int, val b1: Int)
    val queue = mutableListOf(Range(0, old.length, 0, updated.length))
    val matches = mutableListOf<Match>()
    while (queue.isNotEmpty()) {
        val range = queue.removeAt(queue.lastIndex)
        var best = Match(range.a0, range.b0, 0)
        var previous = emptyMap<Int, Int>()
        for (i in range.a0 until range.a1) {
            val current = mutableMapOf<Int, Int>()
            for (j in positions[old[i]].orEmpty()) {
                if (j < range.b0) continue
                if (j >= range.b1) break
                val size = (previous[j - 1] ?: 0) + 1
                current[j] = size
                if (size > best.size) best = Match(i - size + 1, j - size + 1, size)
            }
            previous = current
        }
        if (best.size > 0) {
            matches += best
            if (range.a0 < best.a && range.b0 < best.b) queue += Range(range.a0, best.a, range.b0, best.b)
            if (best.a + best.size < range.a1 && best.b + best.size < range.b1) queue += Range(best.a + best.size, range.a1, best.b + best.size, range.b1)
        }
    }
    val merged = mutableListOf<Match>()
    for (match in matches.sortedWith(compareBy<Match> { it.a }.thenBy { it.b })) {
        val last = merged.lastOrNull()
        if (last != null && last.a + last.size == match.a && last.b + last.size == match.b) merged[merged.lastIndex] = last.copy(size = last.size + match.size)
        else merged += match
    }
    merged += Match(old.length, updated.length, 0)
    val changes = mutableListOf<TextChange>()
    var a = 0; var b = 0
    for (match in merged) {
        if (a < match.a || b < match.b) {
            val change = TextChange(a, match.a, updated.substring(b, match.b))
            val last = changes.lastOrNull()
            if (last != null && change.start - last.end < 4) {
                changes[changes.lastIndex] = TextChange(last.start, change.end, last.text + old.substring(last.end, change.start) + change.text)
            } else changes += change
        }
        a = match.a + match.size; b = match.b + match.size
    }
    return changes
}

private data class Block(val tag: String, val gap: String, var xml: String)
data class Citation(val number: Int, val keys: List<String>, val text: String) {
    internal var items: List<CitationItem> = emptyList()
    internal var style = "parenthetical"
    internal var page = ""
    internal var prefix = ""
}
private data class Snapshot(
    val xml: List<String>, val items: Map<String, CitationItem>, val nextCitation: Int,
    val nextBookmark: Int, val citations: List<Citation>, val removed: Int, val added: Int,
)

class DocxProject(private val source: File) {
    private val prefix: String
    private val suffix: String
    private val tail: String
    private val bom: Boolean
    private val originalXml: String
    private val blocks: MutableList<Block>
    private val history = mutableListOf<Snapshot>()
    private var known = linkedMapOf<String, CitationItem>()
    private var citationOperations = mutableListOf<Citation>()
    private var removed = 0
    private var added = 0
    private var nextCitation: Int
    private var nextBookmark: Int
    init {
        val bytes = ZipFile(source).use { zip -> zip.getInputStream(zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("Missing document.xml")).use { it.readBytes() } }
        bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        val xml = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        originalXml = xml
        val body = Regex("<w:body\\b[^>]*>").find(xml) ?: throw IllegalArgumentException("This file has no document body.")
        val lo = body.range.last + 1; val hi = xml.lastIndexOf("</w:body>")
        require(hi >= lo) { "This file has no document body." }
        prefix = xml.substring(0, lo); suffix = xml.substring(hi)
        blocks = mutableListOf()
        var cursor = lo
        for (e in children(xml, lo, hi)) { blocks += Block(e.tag, xml.substring(cursor, e.start), xml.substring(e.start, e.end)); cursor = e.end }
        tail = xml.substring(cursor, hi)
        nextBookmark = (Regex("<w:bookmarkStart\\b[^>]*\\bw:id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0) + 1
        nextCitation = (bookmarkPattern.findAll(xml).map { it.groupValues[2].toInt() }.maxOrNull() ?: 0) + 1
    }
    val blockCount get() = blocks.size
    fun blockText(index: Int): String {
        val block = blocks[index]
        return when (block.tag) {
            "w:p" -> Paragraph(block.xml).text
            "w:tbl" -> Regex("<w:tc\\b.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(block.xml).joinToString(" | ") { containerText(it.value) }
            else -> containerText(block.xml)
        }
    }
    fun blockKind(index: Int): String {
        val block = blocks[index]
        if (block.tag == "w:tbl") return "table"
        if (block.tag != "w:p") return "other"
        val style = Regex("<w:pStyle w:val=\"([^\"]+)\"").find(block.xml)?.groupValues?.get(1)
        val heading = style?.let { Regex("^heading\\s*(\\d)", RegexOption.IGNORE_CASE).find(it) }
        if (heading != null) return "heading${heading.groupValues[1]}"
        if (style != null && Regex("^(title|subtitle)", RegexOption.IGNORE_CASE).containsMatchIn(style)) return "heading1"
        val paragraph = Paragraph(block.xml)
        val runs = paragraph.segments.filter { it.kind == "text" && it.text.isNotBlank() }
        if (paragraph.text.trim().isNotEmpty() && paragraph.text.trim().length < 90 && runs.isNotEmpty() && runs.all { Regex("<w:b/>|<w:b w:val=\"(1|true)\"").containsMatchIn(it.rpr) }) return "heading2"
        return "paragraph"
    }
    private fun paragraph(index: Int): Paragraph {
        if (index !in blocks.indices || blocks[index].tag != "w:p") throw NotEditable("Blaykie can only edit paragraphs.")
        return Paragraph(blocks[index].xml)
    }
    fun citations(): List<Citation> = citationOperations.toList()
    fun editSummary(): Pair<Int, Int> = removed to added
    private fun snapshot() = Snapshot(blocks.map { it.xml }, known.toMap(), nextCitation, nextBookmark, citationOperations.toList(), removed, added)
    private fun change(index: Int, xml: String, previous: Snapshot) { blocks[index].xml = xml; history += previous }
    fun insertCitation(block: Int, start: Int, end: Int, items: List<CitationItem>, style: String = "parenthetical", page: String = "", prefix: String = ""): Int {
        val chosen = items.distinctBy { it.key }
        require(chosen.isNotEmpty()) { "Choose at least one reference." }
        val para = paragraph(block)
        if (start < 0 || end < start || end > para.text.length) throw NotEditable("Position is outside the paragraph.")
        val before = para.text.getOrNull(start - 1); val after = para.text.getOrNull(end)
        val lead = if (before != null && !before.isWhitespace() && before !in "([{\u201c\u2018\"'/\u2014") " " else ""
        val trail = if (after != null && (after.isLetterOrDigit() || after in "([{\u201c\u2018")) " " else ""
        val ids = chosen.indices.map { nextBookmark + it }
        val rpr = para.format(start, end)
        val marks = chosen.zip(ids).joinToString("") { (item, id) -> "<w:bookmarkStart w:id=\"$id\" w:name=\"BLK_${itemKey(item)}_$nextCitation\"/>" }
        val citationText = CitationFormatting.citationText(chosen, style, page, prefix)
        val replacement = makeRun(rpr, lead) + marks + makeRun(rpr, citationText) + ids.reversed().joinToString("") { "<w:bookmarkEnd w:id=\"$it\"/>" } + makeRun(rpr, trail)
        val newXml = para.replace(start, end, replacement)
        val old = snapshot(); chosen.forEach { known[itemKey(it)] = it }; val number = nextCitation++
        citationOperations.add(Citation(number, chosen.map { it.key }, citationText).apply {
            this.items = chosen; this.style = style; this.page = page; this.prefix = prefix
        })
        nextBookmark += chosen.size; change(block, newXml, old)
        return number
    }
    fun deleteRange(block: Int, start: Int, end: Int, tidy: Boolean = true) {
        val para = paragraph(block)
        var from = start
        if (tidy && from > 0 && from <= para.text.length && para.text[from - 1] == ' ' && (end == para.text.length || (end in para.text.indices && para.text[end] in " .,;:!?)"))) from--
        val updated = if (from == end) blocks[block].xml else para.replace(from, end, "")
        val cleaned = Regex("<w:bookmarkStart w:id=\"(\\d+)\" w:name=\"BLK_[A-Z0-9]+_\\d+\"/><w:bookmarkEnd w:id=\"\\1\"/>").replace(updated, "")
        val old = snapshot()
        removed += end - from
        change(block, cleaned, old)
    }
    /** Atomically apply all paragraph changes as one undoable operation. */
    fun applyTextEdit(block: Int, newText: String): Boolean {
        val original = paragraph(block).text
        if (original == newText) return false
        val changes = textChanges(original, newText)
        if (changes.any { change -> change.text.any { it == '\n' || it == '\r' || it == '\t' } }) {
            throw NotEditable("Line breaks and tabs cannot be typed here.")
        }
        var xml = blocks[block].xml
        var removedCount = 0; var addedCount = 0
        for (change in changes.asReversed()) {
            val para = Paragraph(xml)
            val replacement = makeRun(para.format(change.start, change.end), change.text)
            xml = para.replace(change.start, change.end, replacement)
            xml = Regex("<w:bookmarkStart w:id=\"(\\d+)\" w:name=\"BLK_[A-Z0-9]+_\\d+\"/><w:bookmarkEnd w:id=\"\\1\"/>").replace(xml, "")
            removedCount += change.end - change.start; addedCount += change.text.length
        }
        if (Paragraph(xml).text != newText) throw NotEditable("That change could not be applied exactly.")
        val old = snapshot()
        removed += removedCount; added += addedCount
        change(block, xml, old)
        return true
    }
    fun replaceText(block: Int, start: Int, end: Int, text: String) {
        require(!text.any { it == '\n' || it == '\r' || it == '\t' })
        val para = paragraph(block)
        val xml = para.replace(start, end, makeRun(para.format(start, end), text))
        val old = snapshot()
        removed += end - start; added += text.length
        change(block, xml, old)
    }
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        val snap = history.removeAt(history.lastIndex)
        blocks.forEachIndexed { index, block -> block.xml = snap.xml[index] }
        known = LinkedHashMap(snap.items); nextCitation = snap.nextCitation; nextBookmark = snap.nextBookmark
        citationOperations = snap.citations.toMutableList(); removed = snap.removed; added = snap.added
        return true
    }
    fun presentKeys(): Set<String> = blocks.flatMap { bookmarkPattern.findAll(it.xml).map { m -> m.groupValues[1] }.toList() }.toSet()
    private fun itemKey(item: CitationItem) = item.key.replace(Regex("[^A-Za-z0-9]"), "").uppercase().take(12).ifEmpty { "ITEM" }
    fun existingReferenceHeadings(): List<Int> {
        val names = setOf("references", "reference list", "bibliography", "works cited")
        return blocks.indices.filter { blocks[it].tag == "w:p" && blockText(it).trim().lowercase() in names }
    }
    fun hasBlaykieBibliography(): Boolean = originalXml.contains("BLK_BIBLIOGRAPHY")
    fun hasTrackedChanges(): Boolean = Regex("<w:(ins|del)\\b").containsMatchIn(originalXml)
    fun hasZoteroFields(): Boolean = Regex("ZOTERO_ITEM|ZOTERO_BIBL|CSL_CITATION|CSL_BIBLIOGRAPHY").containsMatchIn(originalXml)
    fun missingKeys(): Set<String> = presentKeys() - known.keys
    fun registerItems(items: List<CitationItem>) { items.forEach { known[itemKey(it)] = it } }
    private fun bodyRuns(): List<Pair<Block, Segment>> = blocks.filter { it.tag == "w:p" }.flatMap { block ->
        Paragraph(block.xml).segments.filter { it.kind == "text" && it.text.codePointCount(0, it.text.length) >= 30 }.map { block to it }
    }
    private fun lineSpacing(line: String): Int = when (line) {
        "match" -> bodyRuns().mapNotNull { (block, _) -> Regex("<w:spacing\\b[^>]*\\bw:line=\"(\\d+)\"").find(block.xml)?.groupValues?.get(1)?.toIntOrNull() }
            .groupingBy { it }.eachCount().entries.maxByOrNull { it.value }?.key ?: 240
        "1.5" -> 360
        "double" -> 480
        else -> throw IllegalArgumentException("Invalid line spacing")
    }
    private fun bibliography(items: List<CitationItem>, bookmark: Int, line: String): String {
        val spacing = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"${lineSpacing(line)}\" w:lineRule=\"auto\"/>"
        val base = bodyRuns().firstOrNull()?.second?.rpr.orEmpty()
        val fonts = Regex("<w:rFonts\\b[^>]*/>").find(base)?.value.orEmpty()
        val size = Regex("<w:sz\\b[^>]*/>").find(base)?.value.orEmpty() + Regex("<w:szCs\\b[^>]*/>").find(base)?.value.orEmpty()
        fun run(text: String, italic: Boolean = false, bold: Boolean = false) = makeRun("<w:rPr>$fonts${if (bold) "<w:b/>" else ""}${if (italic) "<w:i/>" else ""}$size</w:rPr>", text)
        val heading = "<w:p><w:pPr><w:pageBreakBefore/>$spacing<w:jc w:val=\"center\"/></w:pPr>${run("References", bold = true)}</w:p>"
        val suffixes = CitationFormatting.suffixes(items)
        val entries = CitationFormatting.ordered(items).joinToString("") { item ->
            val merged = mutableListOf<Pair<String, Boolean>>()
            CitationFormatting.reference(item, suffixes[item.key].orEmpty()).filter { it.first.isNotEmpty() }.forEach { part ->
                if (merged.isNotEmpty() && merged.last().second == part.second) {
                    val prev = merged.removeAt(merged.lastIndex); merged.add((prev.first + part.first) to part.second)
                } else merged.add(part)
            }
            val runs = merged.joinToString("") { (text, italic) -> run(text, italic) }
            "<w:p><w:pPr>$spacing<w:ind w:left=\"720\" w:hanging=\"720\"/></w:pPr>$runs</w:p>"
        }
        return "<w:bookmarkStart w:id=\"$bookmark\" w:name=\"BLK_BIBLIOGRAPHY\"/>$heading$entries<w:bookmarkEnd w:id=\"$bookmark\"/>"
    }
    fun buildXml(line: String = "match", allowMissing: Boolean = false): String {
        val present = presentKeys()
        require(allowMissing || present.all { it in known }) { "References for existing citations are needed." }
        val items = present.sorted().mapNotNull { known[it] }
        var joined = prefix + blocks.joinToString("") { it.gap + it.xml } + tail + suffix
        for (citation in citationOperations) {
            val first = citation.items.firstOrNull() ?: continue
            val name = "BLK_${itemKey(first)}_${citation.number}"
            val pattern = Regex("(<w:bookmarkStart w:id=\"\\d+\" w:name=\"${Regex.escape(name)}\"/>(?:<w:bookmarkStart [^>]*/>)*<w:r>(?:<w:rPr>.*?</w:rPr>)?<w:t xml:space=\"preserve\">)(.*?)(</w:t></w:r>)", RegexOption.DOT_MATCHES_ALL)
            val fresh = escape(CitationFormatting.citationText(citation.items, citation.style, citation.page, citation.prefix, items))
            pattern.find(joined)?.let { match ->
                joined = joined.replaceRange(match.range, match.groupValues[1] + fresh + match.groupValues[3])
            }
        }
        if (items.isEmpty()) return joined
        val bodyOpen = Regex("<w:body\\b[^>]*>").find(joined) ?: throw IllegalArgumentException("Missing body")
        val lo = bodyOpen.range.last + 1; val hi = joined.lastIndexOf("</w:body>")
        val elements = children(joined, lo, hi)
        val rebuilt = mutableListOf<Block>(); var cursor = lo
        for (element in elements) { rebuilt += Block(element.tag, joined.substring(cursor, element.start), joined.substring(element.start, element.end)); cursor = element.end }
        val tailGap = joined.substring(cursor, hi)
        val maxId = Regex("<w:bookmarkStart\\b[^>]*\\bw:id=\"(\\d+)\"").findAll(joined).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        val bibliography = bibliography(items, maxId + 1, line)
        val start = rebuilt.indexOfFirst { it.tag == "w:bookmarkStart" && it.xml.contains("BLK_BIBLIOGRAPHY") }
        val end = if (start < 0) -1 else (start + 1 until rebuilt.size).firstOrNull { rebuilt[it].tag == "w:bookmarkEnd" } ?: -1
        val body = if (start >= 0 && end >= 0) {
            rebuilt.take(start).joinToString("") { it.gap + it.xml } + bibliography + rebuilt.drop(end + 1).joinToString("") { it.gap + it.xml }
        } else {
            val at = if (rebuilt.lastOrNull()?.tag == "w:sectPr") rebuilt.lastIndex else rebuilt.size
            rebuilt.take(at).joinToString("") { it.gap + it.xml } + bibliography + rebuilt.drop(at).joinToString("") { it.gap + it.xml }
        }
        return joined.substring(0, lo) + body + tailGap + joined.substring(hi)
    }
    fun export(target: File, line: String = "match", allowMissing: Boolean = false) {
        require(source.canonicalFile != target.canonicalFile) { "Choose a different file name. The original is never changed." }
        if (target.exists()) {
            // Reflection keeps java.nio.file (API 26) optional on Android 24/25.
            val sameFile = try {
                val pathClass = Class.forName("java.nio.file.Path")
                val toPath = File::class.java.getMethod("toPath")
                val files = Class.forName("java.nio.file.Files")
                files.getMethod("isSameFile", pathClass, pathClass).invoke(null, toPath.invoke(source), toPath.invoke(target)) as Boolean
            } catch (_: Exception) { true } // Fail closed when identity cannot be verified.
            require(!sameFile) { "Choose a different file name. The original is never changed." }
        }
        val text = buildXml(line, allowMissing)
        val bytes = ((if (bom) "\uFEFF" else "") + text).toByteArray(Charsets.UTF_8)
        ZipFile(source).use { input ->
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val data = if (entry.name == "word/document.xml") bytes else input.getInputStream(entry).use { it.readBytes() }
                    val copy = ZipEntry(entry.name)
                    copy.time = entry.time
                    copy.comment = entry.comment
                    copy.extra = entry.extra
                    output.putNextEntry(copy); output.write(data); output.closeEntry()
                }
            }
        }
    }
}
