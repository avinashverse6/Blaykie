package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.citation.processor.RichRun

/** Deliberately small citeproc HTML subset. Unknown markup and entities are errors. */
internal object CslRichText {
    private const val MAX_INPUT = 1_000_000
    private const val MAX_RUNS = 10_000
    private const val MAX_DEPTH = 16
    private data class Frame(val tag: String, val italic: Boolean, val bold: Boolean, val underline: Boolean, val smallCaps: Boolean)
    fun parse(input: String): List<RichRun> {
        require(input.length <= MAX_INPUT) { "CSL markup too large" }
        val text = input.trim()
        val stack = mutableListOf(Frame("", false, false, false, false))
        val runs = mutableListOf<RichRun>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isEmpty()) return
            val f = stack.last(); val current = RichRun(pending.toString(), f.italic, f.bold, f.underline, f.smallCaps)
            pending.clear()
            val last = runs.lastOrNull()
            if (last != null && last.copy(text = "") == current.copy(text = "")) runs[runs.lastIndex] = last.copy(text = last.text + current.text)
            else { require(runs.size < MAX_RUNS) { "Too many CSL runs" }; runs.add(current) }
        }
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '<' -> {
                    flush()
                    val end = text.indexOf('>', i + 1); require(end >= 0) { "Unterminated tag" }
                    val token = text.substring(i + 1, end)
                    if (token.startsWith("/")) {
                        val tag = token.substring(1)
                        require(stack.size > 1 && stack.last().tag == tag) { "Mismatched CSL tag" }
                        stack.removeAt(stack.lastIndex)
                    } else {
                        val current = stack.last()
                        val next = when (token) {
                            "div class=\"csl-entry\"" -> { require(stack.size == 1 && i == 0) { "Invalid entry wrapper" }; current.copy(tag = "div") }
                            "i" -> current.copy(tag = "i", italic = true)
                            "b" -> current.copy(tag = "b", bold = true)
                            "u" -> current.copy(tag = "u", underline = true)
                            "span style=\"font-variant:small-caps;\"" -> current.copy(tag = "span", smallCaps = true)
                            "span style=\"font-style:italic;\"" -> current.copy(tag = "span", italic = true)
                            "span style=\"font-weight:bold;\"" -> current.copy(tag = "span", bold = true)
                            "span style=\"text-decoration:underline;\"" -> current.copy(tag = "span", underline = true)
                            else -> throw IllegalArgumentException("Unexpected CSL tag")
                        }
                        require(stack.size <= MAX_DEPTH) { "CSL markup too deep" }; stack.add(next)
                    }
                    i = end + 1
                }
                '&' -> {
                    val end = text.indexOf(';', i + 1); require(end in (i + 2)..minOf(i + 12, text.lastIndex)) { "Invalid entity" }
                    val entity = text.substring(i + 1, end)
                    val code = when (entity) {
                        "amp" -> 38; "lt" -> 60; "gt" -> 62; "quot" -> 34; "apos", "#39" -> 39; "nbsp" -> 160
                        else -> if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16) else if (entity.startsWith("#")) entity.substring(1).toIntOrNull() else null
                    }
                    require(code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF && code >= 0x20) { "Invalid entity" }
                    pending.appendCodePoint(code); i = end + 1
                }
                else -> { require(text[i] != '\u0000') { "Invalid character" }; pending.append(text[i]); i++ }
            }
        }
        flush(); require(stack.size == 1) { "Unclosed CSL tag" }
        return runs
    }
}
