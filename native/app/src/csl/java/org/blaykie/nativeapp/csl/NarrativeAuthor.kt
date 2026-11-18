package org.blaykie.nativeapp.csl

/** Replace only a separator outside known literal group names; leave ambiguous output alone. */
internal fun narrativeAuthor(authorOnly: String, localeAnd: String, authorCount: Int, groupNames: List<String> = emptyList()): String {
    if (authorCount < 2 || localeAnd.isBlank()) return authorOnly
    val ranges = groupNames.filter { it.isNotBlank() }.flatMap { name ->
        val result = mutableListOf<IntRange>()
        var from = 0
        while (from < authorOnly.length) {
            val at = authorOnly.indexOf(name, from)
            if (at < 0) break
            result.add(at until at + name.length)
            from = at + name.length
        }
        result
    }
    val candidates = listOf(" & ", " &amp; ").flatMap { separator ->
        val positions = mutableListOf<Pair<String, Int>>()
        var from = 0
        while (from < authorOnly.length) {
            val at = authorOnly.indexOf(separator, from)
            if (at < 0) break
            if (ranges.none { at >= it.first && at + separator.length - 1 <= it.last }) positions.add(separator to at)
            from = at + separator.length
        }
        positions
    }
    // Multiple remaining ampersands are ambiguous: do not guess which is a name separator.
    if (candidates.size != 1) return authorOnly
    val (separator, at) = candidates.single()
    return authorOnly.replaceRange(at, at + separator.length, " ${localeAnd.trim()} ")
}
