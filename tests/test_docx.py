"""Tests for blaykie_docx: insert, delete, undo and export on a small
document built with python-docx. Needs no Kivy."""

import os
import tempfile
import unittest

from docx import Document

from blaykie_docx import DocxProject

FIRST = "First paragraph about citations."
SECOND = "Second paragraph with enough words to edit later on."

ITEM = {
    "key": "ABCD2345",
    "data": {
        "itemType": "journalArticle",
        "title": "Citation Studies in Practice",
        "creators": [
            {"firstName": "Ada", "lastName": "Lovelace", "creatorType": "author"},
        ],
        "date": "2020",
        "publicationTitle": "Journal of Testing",
        "volume": "12",
        "issue": "3",
        "pages": "1-10",
        "DOI": "10.1234/test.2020",
    },
}


def build_docx(path):
    document = Document()
    document.add_paragraph(FIRST)
    document.add_paragraph(SECOND)
    document.save(path)


class DocxTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = os.path.join(self.tmp.name, "paper.docx")
        build_docx(self.source)
        self.project = DocxProject(self.source)

    # ----- insert -----

    def test_insert_citation(self):
        position = len(FIRST)
        number = self.project.insert_citation(0, position, position, [ITEM])
        text = self.project.block_text(0)
        self.assertTrue(text.startswith(FIRST))
        self.assertIn("(Lovelace, 2020)", text)
        self.assertEqual(number, 1)
        self.assertEqual(self.project.citations(),
                         [(1, ["ABCD2345"], "(Lovelace, 2020)")])
        self.assertEqual(self.project.present_keys(), {"ABCD2345"})

    # ----- delete -----

    def test_delete_range(self):
        start = SECOND.index("enough")
        end = start + len("enough")
        self.project.delete_range(1, start, end, tidy=False)
        self.assertEqual(self.project.block_text(1),
                         SECOND[:start] + SECOND[end:])
        self.assertEqual(self.project.edit_summary(), (6, 0))

    # ----- undo -----

    def test_undo_restores_original_text(self):
        self.project.insert_citation(0, len(FIRST), len(FIRST), [ITEM])
        self.project.delete_range(1, 0, 6, tidy=False)
        self.assertTrue(self.project.undo())
        self.assertEqual(self.project.block_text(1), SECOND)
        self.assertTrue(self.project.undo())
        self.assertEqual(self.project.block_text(0), FIRST)
        self.assertEqual(self.project.citations(), [])
        self.assertEqual(self.project.present_keys(), set())
        self.assertFalse(self.project.undo())

    # ----- export -----

    def test_export_writes_new_file_and_leaves_source_alone(self):
        self.project.insert_citation(0, len(FIRST), len(FIRST), [ITEM])
        with open(self.source, "rb") as file:
            before = file.read()
        out = os.path.join(self.tmp.name, "paper_Blaykie.docx")
        self.project.export(out)
        with open(self.source, "rb") as file:
            after = file.read()
        self.assertEqual(before, after, "the original file was modified")

        paragraphs = [p.text for p in Document(out).paragraphs]
        self.assertEqual(paragraphs[0], FIRST + " (Lovelace, 2020)")
        self.assertEqual(paragraphs[1], SECOND)
        self.assertIn("References", paragraphs)
        reference = [p for p in paragraphs
                     if "Lovelace, A. (2020)" in p and "Journal of Testing" in p]
        self.assertEqual(len(reference), 1)

    def test_export_refuses_to_overwrite_the_source(self):
        with self.assertRaises(ValueError):
            self.project.export(self.source)


if __name__ == "__main__":
    unittest.main()
