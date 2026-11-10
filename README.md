# Blaykie

**Cite smarter. APA 7 in seconds.**

## Native Android app

The native app opens a Word `.docx`, lets you edit supported paragraphs and insert citations from your Zotero library, and exports a new document with references. Review the exported document and keep the original.

### Install

When a native release is published, download its APK from the [Releases](../../releases) page, open it on an Android 7.0 (API 24) or newer device, and allow installation from your chosen source if Android asks. Verify the APK using the accompanying SHA-256 checksum; a debug build is a separate app and does not share the release app's data.

### Privacy

Your Zotero API key is stored encrypted on the phone. The app has no analytics and sends app network requests only to `api.zotero.org`; the key-creation button opens `zotero.org` in your browser.

### Known limitations

- A Zotero API key must be supplied manually for now.
- Documents are presented as paragraph cards rather than a page view.
- Tables and protected document content cannot be edited; review formatting after export.

## Kivy app

The remainder of this document describes the existing Kivy app and its build process.

Blaykie is an Android app that inserts APA 7 citations from your [Zotero](https://www.zotero.org) library straight into your own Word document, right where you tap, and builds the reference list for you. It then exports a **new** `.docx`. Your original file is never changed.

Blaykie is not a word processor. It does one job: citing from Zotero into your document, on your phone.

## How it works

1. Open your `.docx` file.
2. Tap the paragraph where the citation should go, and place the cursor.
3. Search your Zotero library and pick one or more references.
4. Choose the style, add a page number or prefix if needed, check the live preview, and insert.
5. Export. Blaykie adds a formatted **References** section and saves `<document>_Blaykie.docx`.

## Features

- **Cite in place.** Insert at the cursor, or right after a selection. Blaykie never deletes or replaces your text when inserting.
- **Zotero search.** Searches your personal library through the official Zotero Web API (up to 60 results, with "Show more").
- **Citation styles.** `(Author, 2020)` or `Author (2020)`, with optional page number and prefix such as "see".
- **Multiple references at once.** Alphabetical order, same-author years merged, `2020a` / `2020b` suffixes, initials when surnames clash, and APA et al. rules.
- **APA 7 reference list.** Hanging indent, italic journal and book titles, up to 20 authors, DOI links, "n.d.", "in press", group authors, and no-author works.
- **Many source types.** Journal article, book, edited book, chapter, report, thesis, web page, blog post, newspaper, magazine, conference paper, preprint and generic items.
- **Edit paragraph text.** Type, cut, copy and paste inside a paragraph. Everything is undoable.
- **Undo.** Every insert and edit can be undone.
- **Resume.** Close the app and continue exactly where you left off.
- **Re-open exported files.** Open a Blaykie export again and keep citing. The reference list is rebuilt, not duplicated.
- **Line spacing on export.** Like your document (default), 1.5 lines, or double (APA).
- **Safe by design.** Zotero plugin fields, hyperlinks, tracked changes and tables are protected from edits. Only the spot you touch is changed; everything else in the file is copied unchanged.
- **Private.** Your Zotero ID and key stay on your phone. The app talks only to `api.zotero.org`.

## Install

Download the APK that matches your phone from the [Releases](../../releases) page:

| Phone | APK |
|-------|-----|
| 32-bit | `armeabi-v7a` |
| 64-bit (most recent phones) | `arm64-v8a` |

Requires Android 7.0 (API 24) or newer.

> APKs are signed with a debug key that changes with every build. To install a newer version, **uninstall the old one first** and enter your Zotero details again.

## Getting your Zotero credentials

1. Sign in at [zotero.org](https://www.zotero.org) and open **Settings → Security**.
2. Note your numeric **User ID** ("Your userID for use in API calls").
3. Create a new private key with **read** access to your personal library.
4. In Blaykie, tap the gear icon and enter the User ID and API key.

## Current limits

- APA 7 only.
- Personal Zotero library only (group libraries are not supported yet).
- Citations cannot be placed inside tables or footnotes yet.
- Existing Blaykie citations cannot yet be edited by tapping them. Use Undo, or cut and re-insert.
- Titles are used exactly as stored in Zotero. No automatic sentence-case conversion is applied.

## Project structure

| File | Purpose |
|------|---------|
| `main.py` | App screens and flow (Kivy) |
| `ui.py` | Colours and custom widgets |
| `blaykie_docx.py` | `.docx` engine: citations, edits, undo, bibliography, export |
| `zotero_engine.py` | Zotero API search and APA 7 formatting |
| `buildozer.spec` | Android build configuration |
| `.github/workflows/build-apk.yml` | GitHub Actions workflow that builds both APKs |

## Building the APK

The easiest way is GitHub Actions: open the **Actions** tab, choose **Build Android APK**, and click **Run workflow**. It builds one APK per processor type (`armeabi-v7a` and `arm64-v8a`) and uploads them as artifacts.

To build locally (Linux or WSL):

```bash
pip install buildozer cython==0.29.37
buildozer -v android debug
```

The APK is written to the `bin/` folder. Set `android.archs` in `buildozer.spec` to build one processor type at a time.

## Citation metadata privacy (experimental)

The native citation-record codec is under development and is **not yet connected to DOCX export**. Its proposed records contain the document style; each citation's UUID, bookmark name, mode, page locator and label, and prefix; and each item's Zotero item key, title, author and editor first/last/group names, date, publication title, volume, issue, pages, DOI, item type, publisher, book title, edition, URL, editor-lead flag, website type/title, blog title, report number/type, institution, thesis type, university, archive, proceedings title, conference name, place and repository. It discards free-form Extra except for a DOI used by the formatter. There is no field for a Zotero account ID or API key.

Bibliographic text and URLs can themselves contain sensitive information. Review the data you put in citations before sharing a document; the codec does not scan or redact free text.

## Contributing

Bug reports and suggestions are welcome. Please open an issue.

## License

Copyright (C) 2026 avinashverse6

Licensed under AGPL-3.0-or-later. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Versions released before this license change were MIT-licensed and remain MIT-licensed for those versions. The last commit on main carrying the MIT license was `2971cc3c3bcb86b497cf19e5d5c32263a1c02b6d` (2026-11-07 07:08:43 +05:30).

For other license requests, please use this repository's GitHub page.

Blaykie is not affiliated with Zotero.
