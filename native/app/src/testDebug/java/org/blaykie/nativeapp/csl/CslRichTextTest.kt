package org.blaykie.nativeapp.csl

import org.blaykie.nativeapp.citation.processor.RichRun
import org.junit.Assert.*
import org.junit.Test

class CslRichTextTest {
    @Test fun parsesFormattingAndEntities() {
        val result = CslRichText.parse("""<div class="csl-entry">A &amp; B <i>Title <b>bold</b></i> <u>under</u> <span style="font-variant:small-caps;">Caps</span>.</div>""")
        assertEquals(listOf(RichRun("A & B "), RichRun("Title ", italic=true), RichRun("bold", italic=true, bold=true), RichRun(" "), RichRun("under", underline=true), RichRun(" "), RichRun("Caps", smallCaps=true), RichRun(".")), result)
    }
    @Test fun rejectsUnexpectedMalformedAndOversizedMarkup() {
        listOf("<script>alert(1)</script>", "<div class=\"csl-entry\">x</div>extra", "bad\u0001text", "<i>bad</b>", "<i>unclosed", "a &bogus;", "<i onclick=\"x\">a</i>", "<div class=\"csl-entry\"><div class=\"csl-entry\">x</div></div>", "x".repeat(1_000_001)).forEach { text ->
            try { CslRichText.parse(text); fail("Unexpected markup accepted") } catch (expected: IllegalArgumentException) { }
        }
    }
}
