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

B2-2/B2-2b: dependent parent lookup, unavailable-parent handling and timed sample previews are implemented in debug; timeout, failure and cancellation are tested without changing the active session. Phone verification remains outstanding.

B2-3b/B2-3c: debug-only locale import/removal, session language selection and persistence, style default-language selection for independent and dependent styles, English term fallback, picker/export language summaries, and JVM/Rhino tests are implemented. Explicit language selection overrides defaults; users can return to automatic. Built-in APA remains the default and uses English. Older sessions with a saved non-English language preserve it as explicit; older English selections cannot be distinguished from the former default. CI passed debug tests and unchanged release isolation: https://github.com/avinashverse6/Blaykie/actions/runs/43571755181. Phone verification remains pending; fictional test files are in docs/csl/phone-fixtures/.

B3-1a: the duplicated export controller and screen are unified in main behind a neutral processor contract and variant-specific export hooks. Release uses the unchanged built-in writer and a no-op hook; optional preparation and presentation remain debug-only. Existing export tests, sample-document tests and the unchanged release-isolation check passed: https://github.com/avinashverse6/Blaykie/actions/runs/43574691834.

B3-1b-1: the optional runtime, its feature-aware document/session adapters and the two verified processor assets are in `src/csl`, included in debug and in release only with `-PcslInRelease=true` (default false). Developer spikes, comparison helper and bundled APA style stay debug-only; the enabled release registry has no bundled style. The default release and its isolation check remain unchanged. The debug tests and the informational enabled-release compile and unit tests passed: https://github.com/avinashverse6/Blaykie/actions/runs/43576813115. The unused comparison converter was subsequently returned to debug-only; its CI recheck is pending.

B3-1b remains **incomplete**: the persisted off-by-default setting is not exposed or connected to all host, insertion and export paths; a stricter release isolation check must be made separately when release integration is ready. The informational flag-on build is not a releasable build. B3-2 licenses must be completed before any release.

Phone checks done so far: host self-test, style picker and insertion checklist; all three sample exports opened in Google Docs. The CSL APA sample was reported without visible hanging indents, and the legacy sample was reported as formatted differently from built-in APA (details unspecified). Rechecking the revised samples remains outstanding. B2-1 phone check: an imported ASA style worked and its exported references appeared correct in Google Docs.



## Remaining steps (one per session)

- B2-1 style import: system file picker, validation (size limit, DOCTYPE and ENTITY rejection), private storage, list.

- B2-2 dependent styles and parent handling, preview with a sample item, clear error messages.

- B2-3 locale (language) file import.

- B3-1 remaining: wire the off-by-default runtime switch and picker into Settings, guard all optional host/insertion/export paths, and update the isolation check in ONE separately reviewed commit. B3-1a unified the export files; B3-1b-1 added a default-off build flag without changing the isolation check.

- B3-2 licenses: CC BY-SA 3.0 legal text (owner uploads it), SOURCES.md, THIRD_PARTY_NOTICES.md, About credit for the CSL project and citeproc-js under its AGPL option.

- B3-3 version 1.1.0 and release notes (the owner creates the tag).



## Known duplication

The former debug/release copies of `export/ExportController.kt` and `export/ExportScreen.kt` are now single implementations under `src/main`. The neutral hook and processor contract also live in main; `src/csl` supplies optional preparation and UI, while default release supplies a no-op hook and provider. Feature-aware and default-release document, session and activity adapters remain variant-specific.



## Working rules for any session

Small commits and a push after each; never force push or reset --hard; never weaken a check; one step per session; end with what is done and what is left; ask before adding a dependency; no tag, release, PR or merge unless the owner asks.
