package org.blaykie.nativeapp.render

import org.w3c.dom.Element

internal data class PageSetup(
    val widthTwips: Int = 11906, val heightTwips: Int = 16838,
    val landscape: Boolean = false,
    val topTwips: Int = 1440, val rightTwips: Int = 1440, val bottomTwips: Int = 1440, val leftTwips: Int = 1440,
    val headerTwips: Int = 720, val footerTwips: Int = 720,
) {
    val contentWidthPoints get() = (widthTwips - leftTwips - rightTwips) / 20f
    val contentHeightPoints get() = (heightTwips - topTwips - bottomTwips) / 20f
}

internal fun readPageSetup(section: Element?): PageSetup {
    val defaults = PageSetup()
    val size = section.get("pgSz")
    val landscape = size.attr("orient") == "landscape"
    val w = size.attr("w")?.toIntOrNull()?.takeIf { it in 1440..31680 } ?: defaults.widthTwips
    val h = size.attr("h")?.toIntOrNull()?.takeIf { it in 1440..31680 } ?: defaults.heightTwips
    val width = if (landscape) maxOf(w, h) else w
    val height = if (landscape) minOf(w, h) else h
    val margin = section.get("pgMar")
    fun edge(name: String, max: Int) = (margin.attr(name)?.toIntOrNull() ?: 1440).coerceIn(0, max)
    val left = edge("left", width - 720)
    val top = edge("top", height - 720)
    return PageSetup(width, height, landscape, top, edge("right", width - left - 720),
        edge("bottom", height - top - 720), left,
        (margin.attr("header")?.toIntOrNull() ?: 720).coerceIn(0, height),
        (margin.attr("footer")?.toIntOrNull() ?: 720).coerceIn(0, height))
}
