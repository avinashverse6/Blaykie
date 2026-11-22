package org.blaykie.nativeapp.render

internal enum class HeaderFooterKind { Default, First, Even }
internal enum class PageField { Page, NumPages, SectionPages }
internal enum class SectionBreak { NextPage, Continuous, EvenPage, OddPage }

/** One document section, with header/footer contents already resolved through inheritance. */
internal data class PrintSection(
    val endBlockIndex: Int,
    val breakType: SectionBreak = SectionBreak.NextPage,
    val titlePage: Boolean = false,
    val pageNumberStart: Int? = null,
    val pageNumberFormat: String? = null,
    val setup: PageSetup = PageSetup(),
    val headers: Map<HeaderFooterKind, List<RenderBlock>> = emptyMap(),
    val footers: Map<HeaderFooterKind, List<RenderBlock>> = emptyMap(),
)

/** A section prepared for pagination: real body area after tall headers/footers push the body. */
internal data class PrintSectionSpan(
    val endBlockIndex: Int,
    val breakType: SectionBreak = SectionBreak.NextPage,
    val titlePage: Boolean = false,
    val pageNumberStart: Int? = null,
    val pageNumberFormat: String? = null,
    val capacityPoints: Float,
    val effectiveTopPoints: Float,
    val effectiveBottomPoints: Float,
    val headerDistancePoints: Float,
    val footerDistancePoints: Float,
    val headers: Map<HeaderFooterKind, List<RenderBlock>> = emptyMap(),
    val footers: Map<HeaderFooterKind, List<RenderBlock>> = emptyMap(),
)

internal fun parsePageField(instruction: String?): PageField? =
    when (instruction?.trim()?.takeWhile { it.isLetter() }?.uppercase()) {
        "PAGE" -> PageField.Page
        "NUMPAGES" -> PageField.NumPages
        "SECTIONPAGES" -> PageField.SectionPages
        else -> null
    }

internal fun formatPageNumber(number: Int, format: String?): String {
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
            for ((value, symbols) in ROMAN_NUMERALS) while (remaining >= value) { append(symbols); remaining -= value }
        }
        return if (format == "lowerRoman") roman.lowercase() else roman
    }
    return number.toString()
}

private val ROMAN_NUMERALS = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
    50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")

/** Replace PAGE/NUMPAGES/SECTIONPAGES field runs with this page's values; cached text is kept elsewhere. */
internal fun RenderParagraph.withPageFields(page: String, pages: String, sectionPages: String): RenderParagraph {
    if (runs.none { it.field != null }) return this
    return copy(runs = runs.map { run ->
        when (run.field) {
            PageField.Page -> run.copy(text = page)
            PageField.NumPages -> run.copy(text = pages)
            PageField.SectionPages -> run.copy(text = sectionPages)
            null -> run
        }
    })
}

internal fun List<RenderBlock>.withPageFields(page: String, pages: String, sectionPages: String): List<RenderBlock> =
    map { if (it is RenderParagraph) it.withPageFields(page, pages, sectionPages) else it }

internal fun buildPrintSectionSpans(sections: List<PrintSection>, evenAndOddHeaders: Boolean,
                                    measureHeight: (List<RenderBlock>) -> Float): List<PrintSectionSpan> =
    sections.map { section ->
        val setup = section.setup
        fun tallest(variants: Map<HeaderFooterKind, List<RenderBlock>>): Float {
            var height = 0f
            for ((kind, blocks) in variants) {
                val used = kind == HeaderFooterKind.Default ||
                    (kind == HeaderFooterKind.First && section.titlePage) ||
                    (kind == HeaderFooterKind.Even && evenAndOddHeaders)
                if (used && blocks.isNotEmpty()) height = maxOf(height, measureHeight(blocks))
            }
            return height
        }
        // Like Word, an over-tall header or footer grows the body margin instead of overlapping text.
        val top = maxOf(setup.topTwips / 20f, setup.headerTwips / 20f + tallest(section.headers))
        val bottom = maxOf(setup.bottomTwips / 20f, setup.footerTwips / 20f + tallest(section.footers))
        PrintSectionSpan(section.endBlockIndex, section.breakType, section.titlePage,
            section.pageNumberStart, section.pageNumberFormat,
            (setup.heightTwips / 20f - top - bottom).coerceAtLeast(1f), top, bottom,
            setup.headerTwips / 20f, setup.footerTwips / 20f, section.headers, section.footers)
    }

internal fun PrintSectionSpan.headerFooterFor(firstOfSection: Boolean, evenPage: Boolean,
                                              evenAndOddHeaders: Boolean): Pair<List<RenderBlock>, List<RenderBlock>> {
    fun pick(variants: Map<HeaderFooterKind, List<RenderBlock>>): List<RenderBlock> = when {
        firstOfSection && titlePage && HeaderFooterKind.First in variants -> variants.getValue(HeaderFooterKind.First)
        evenPage && evenAndOddHeaders && HeaderFooterKind.Even in variants -> variants.getValue(HeaderFooterKind.Even)
        else -> variants[HeaderFooterKind.Default].orEmpty()
    }
    return pick(headers) to pick(footers)
}

/** Stamp every sheet with its formatted page number, totals and its section's header/footer. */
internal fun assignPrintPageNumbers(pages: List<PrintPage>, spans: List<PrintSectionSpan>,
                                    evenAndOddHeaders: Boolean): List<PrintPage> {
    if (spans.isEmpty()) return pages
    val sectionTotals = pages.groupingBy { it.sectionIndex }.eachCount()
    val seen = mutableSetOf<Int>()
    var display = 0
    return pages.map { page ->
        val span = spans[page.sectionIndex.coerceIn(0, spans.lastIndex)]
        val firstOfSection = seen.add(page.sectionIndex)
        display = if (firstOfSection && span.pageNumberStart != null) span.pageNumberStart else display + 1
        val (header, footer) = if (page.blank) emptyList<RenderBlock>() to emptyList()
            else span.headerFooterFor(firstOfSection, display % 2 == 0, evenAndOddHeaders)
        page.copy(sectionIndex = page.sectionIndex.coerceIn(0, spans.lastIndex),
            pageLabel = formatPageNumber(display, span.pageNumberFormat),
            totalLabel = pages.size.toString(),
            sectionTotalLabel = (sectionTotals[page.sectionIndex] ?: 1).toString(),
            firstOfSection = firstOfSection,
            headerBlocks = header, footerBlocks = footer,
            headerDistancePoints = span.headerDistancePoints, footerDistancePoints = span.footerDistancePoints,
            bodyTopPoints = span.effectiveTopPoints, bodyBottomPoints = span.effectiveBottomPoints)
    }
}
