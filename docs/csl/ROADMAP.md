# CSL roadmap (Blaykie)



## Decisions

- Built-in Kotlin APA 7 is the default and the fallback.

- Other styles come only from user-imported .csl files (and optionally a locale file). We do not bundle Harvard, Chicago, ASA or MLA.

- CSL runs in a hidden WebView with citeproc-js. Rhino stays only in JVM tests.

- Supported: independent author-date styles. Numeric, note-based and dependent styles are disabled with a reason (a dependent style needs its parent imported).

- No durable citation records in the DOCX; the codec is parked and unused. Footnote styles are out of scope.

- Known limitation: citeproc-js joins Devanagari and Gujarati family and given names without a comma.

- The " &amp; " between authors in narrative citations is replaced by the locale's "and" in a targeted post-processing step.

- Release isolation: the release app contains no CSL code until step B3-1; the isolation check is never weakened or edited to pass. v1.0.1's Zotero field identifiers CSL_CITATION and CSL_BIBLIOGRAPHY stay in release.



## Status

Done: style registry; processor interface and built-in adapter; session-scoped WebView host with self-test; item converter A (see docs/csl/COMPARISON.md); style picker and session saving; CSL citation insertion; bibliography as structured rich text; export through CSL with explicit fallback; sample-docx-csl CI artifact; B2-1 debug style import, validation, private storage and removal (phone verification pending).

B2-2/B2-2b: dependent parent lookup, unavailable-parent handling and timed sample previews are implemented in debug; timeout, failure and cancellation are tested without changing the active session. The dependent style's own language setting is deferred to B2-3. Phone verification remains outstanding.

Phone checks done so far: host self-test, style picker and insertion checklist; all three sample exports opened in Google Docs. The CSL APA sample was reported without visible hanging indents, and the legacy sample was reported as formatted differently from built-in APA (details unspecified). Rechecking the revised samples remains outstanding. B2-1 phone check: an imported ASA style worked and its exported references appeared correct in Google Docs.



## Remaining steps (one per session)

- B2-1 style import: system file picker, validation (size limit, DOCTYPE and ENTITY rejection), private storage, list.

- B2-2 dependent styles and parent handling, preview with a sample item, clear error messages.

- B2-3 locale (language) file import.

- B3-1 move engine, host and picker into release, picker under Settings, merge the duplicated export files, update the isolation check in ONE reviewed commit that the owner approves.

- B3-2 licenses: CC BY-SA 3.0 legal text (owner uploads it), SOURCES.md, THIRD_PARTY_NOTICES.md, About credit for the CSL project and citeproc-js under its AGPL option.

- B3-3 version 1.1.0 and release notes (the owner creates the tag).



## Known duplication

- native/app/src/debug/java/org/blaykie/nativeapp/export/ExportController.kt
- native/app/src/release/java/org/blaykie/nativeapp/export/ExportController.kt
- native/app/src/debug/java/org/blaykie/nativeapp/export/ExportScreen.kt
- native/app/src/release/java/org/blaykie/nativeapp/export/ExportScreen.kt



## Working rules for any session

Small commits and a push after each; never force push or reset --hard; never weaken a check; one step per session; end with what is done and what is left; ask before adding a dependency; no tag, release, PR or merge unless the owner asks.
