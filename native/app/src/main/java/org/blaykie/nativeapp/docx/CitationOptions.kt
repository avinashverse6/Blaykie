package org.blaykie.nativeapp.docx

import kotlinx.serialization.Serializable

@Serializable
enum class LocatorLabel(val title: String, val abbreviation: String) {
    Page("Page", "p."), Chapter("Chapter", "chap."), Paragraph("Paragraph", "para."), Section("Section", "sec.")
}

@Serializable
data class CitationOptions(val suffix: String = "", val omitAuthor: Boolean = false,
                           val label: LocatorLabel = LocatorLabel.Page) {
    internal fun normalized(style: String, locator: String) = copy(suffix = citationField(suffix, 160).trim(),
        omitAuthor = omitAuthor && style != "narrative", label = if (locator.isBlank()) LocatorLabel.Page else label)
    internal val needsApa: Boolean get() = suffix.isNotBlank() || omitAuthor || label != LocatorLabel.Page
}

/** Limit UTF-16 length without splitting supplementary characters or allowing invalid XML. */
internal fun citationField(value: String, limit: Int): String = buildString {
    var index = 0
    while (index < value.length && length < limit) {
        val cp = value.codePointAt(index)
        val count = Character.charCount(cp)
        if (length + count > limit) break
        when (cp) {
            9, 10, 13 -> append(' ')
            in 32..0xd7ff, in 0xe000..0xfffd, in 0x10000..0x10ffff -> appendCodePoint(cp)
        }
        index += count
    }
}
