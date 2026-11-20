package org.blaykie.nativeapp.render

import java.util.Locale

internal enum class DocumentFontCategory { SansSerif, Serif, Monospace, Cursive }

internal fun documentFontCategory(name: String): DocumentFontCategory = when (name.trim().lowercase(Locale.ROOT)) {
    "serif", "times", "times new roman", "georgia", "cambria", "garamond", "palatino",
    "palatino linotype", "book antiqua", "bookman old style", "baskerville", "constantia", "lora",
    "liberation serif", "noto serif", "source serif pro", "source serif 4" -> DocumentFontCategory.Serif
    "monospace", "courier", "courier new", "consolas", "liberation mono" -> DocumentFontCategory.Monospace
    "cursive" -> DocumentFontCategory.Cursive
    else -> DocumentFontCategory.SansSerif
}
