package org.blaykie.nativeapp.csl

/** Reject combinations not yet verified by the debug preview processor. */
internal fun citationRestriction(styleId: String?, mode: String, itemCount: Int, page: String, prefix: String): String? {
    if (styleId == null) return null
    if (mode == "narrative" && itemCount > 1) return "Narrative CSL citations require one item."
    if (itemCount > 1 && (page.isNotBlank() || prefix.isNotBlank())) return "Page and prefix require one CSL item."
    if (mode == "narrative" && prefix.isNotBlank() && styleId != "http://www.zotero.org/styles/apa") return "Narrative prefix is not verified for this style."
    return null
}
