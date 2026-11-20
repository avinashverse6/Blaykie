package org.blaykie.nativeapp.docx

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.blaykie.nativeapp.session.EditOperation
import org.blaykie.nativeapp.session.SessionRead
import org.blaykie.nativeapp.session.SessionStore
import org.blaykie.nativeapp.session.UndoResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CitationOptionsTest {
    @get:Rule val temp = TemporaryFolder()
    private val items = listOf(CitationItem("TAGADE20", "Example", listOf(
        CitationAuthor("A", "Tagade"), CitationAuthor("B", "Thorat")), "2020"))

    @Test fun apaLocatorAffixesAndAuthorOptions() {
        fun text(page: String = "", prefix: String = "", style: String = "parenthetical", options: CitationOptions = CitationOptions()) =
            CitationFormatting.citationText(items, style, page, prefix, options = options)
        assertEquals("(Tagade & Thorat, 2020)", text())
        assertEquals("(Tagade & Thorat, 2020, p. 117)", text("117"))
        assertEquals("(see Tagade & Thorat, 2020, pp. 117–120, for details)",
            text("117-120", " see ", options = CitationOptions(suffix = " for details ")))
        assertEquals("(Tagade & Thorat, 2020, chap. 3)", text("3", options = CitationOptions(label = LocatorLabel.Chapter)))
        assertEquals("(Tagade & Thorat, 2020, para. 3)", text("3", options = CitationOptions(label = LocatorLabel.Paragraph)))
        assertEquals("(Tagade & Thorat, 2020, sec. 3)", text("3", options = CitationOptions(label = LocatorLabel.Section)))
        assertEquals("(2020)", text(options = CitationOptions(omitAuthor = true)))
        assertEquals("(2020, p. 117)", text("117", options = CitationOptions(omitAuthor = true)))
        assertEquals("Tagade and Thorat (2020, p. 117)", text("117", style = "narrative"))
        assertEquals("(Tagade & Thorat, 2020, details)", text(options = CitationOptions(suffix = "details")))
        assertEquals("a b", citationField("a\u0000\nb", 4))
        assertEquals("a", citationField("a😀", 2))
    }

    @Test fun editsInPlaceReplaysAndUndoesToExactOriginalXml() {
        val folder = temp.newFolder()
        val source = File(folder, "working.docx")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Before after</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        val store = SessionStore(folder, source)
        val opened = store.attach("content://test", "Test.docx")
        val project = opened.project
        val insert = EditOperation(0, type = "citation", position = 6, items = items, page = "117")
        val number = project.insertCitation(0, 6, 6, items, page = "117")
        val original = project.buildXml()
        val originalText = project.blockText(0)
        val extra = CitationOptions("for <details> & examples", label = LocatorLabel.Chapter)
        val edit = EditOperation(0, type = "editCitation", position = number, page = " 3 ", prefix = "see", citationOptions = extra)
        project.editCitation(0, number, edit.style, edit.page, edit.prefix, extra)
        assertEquals("Before (see Tagade & Thorat, 2020, chap. 3, for <details> & examples) after", project.blockText(0))
        assertTrue(project.buildXml().contains("&lt;details&gt; &amp; examples"))
        val session = opened.session.copy(edits = listOf(insert, edit))
        store.save(session)
        val reopened = SessionStore(folder, source).read() as SessionRead.Ready
        assertEquals(extra, reopened.project.citations().single().options)
        assertEquals(number, reopened.project.citations().single().number)
        assertEquals(project.buildXml(), reopened.project.buildXml())
        val undone = store.undo(session) as UndoResult.Success
        assertEquals(originalText, undone.project.blockText(0))
        assertEquals(original, undone.project.buildXml())
    }
}
