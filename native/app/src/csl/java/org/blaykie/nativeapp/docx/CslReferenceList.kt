package org.blaykie.nativeapp.docx

import org.blaykie.nativeapp.citation.processor.BibliographyResult

internal fun BibliographyResult.referenceList(): ReferenceList = ReferenceList(entries.map { entry ->
    entry.runs.map { ReferenceRun(it.text, it.italic, it.bold, it.underline, it.smallCaps) }
}, layout.hangingIndent == true, layout.entrySpacing)
