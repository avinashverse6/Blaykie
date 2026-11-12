package org.blaykie.nativeapp.csl

/** Replace only the last conjunction between multiple authors, never a single group name. */
internal fun narrativeAuthor(authorOnly: String, localeAnd: String, authorCount: Int): String {
    if (authorCount < 2 || localeAnd.isBlank()) return authorOnly
    val candidates = listOf(" & ", " &amp; ").map { separator -> separator to authorOnly.lastIndexOf(separator) }
    val (separator, at) = candidates.maxByOrNull { it.second } ?: return authorOnly
    if (at < 0) return authorOnly
    return authorOnly.replaceRange(at, at + separator.length, " ${localeAnd.trim()} ")
}
