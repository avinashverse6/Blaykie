# Zotero Citation Manager

A small Android app (built with [Kivy](https://kivy.org)) that searches your [Zotero](https://www.zotero.org) library and copies APA 7 citations to your clipboard, so you can paste them straight into your notes or manuscript from your phone.

## Features

- Search your Zotero library by keyword through the official Zotero Web API
- Select one or more results with checkboxes
- Copy, with one tap:
  - **In-text citation**, e.g. `(Smith & Jones, 2020)`
  - **Narrative citation**, e.g. `Smith and Jones (2020)`
  - **Full APA 7 reference**
- Supports journal articles, books, book sections, theses, reports, web pages and blog posts
- Works with your own library only; nothing is sent anywhere except `api.zotero.org`

## Getting your Zotero credentials

1. Sign in at [zotero.org](https://www.zotero.org) and open **Settings → Security** (the "Applications" / API keys section).
2. Note your **User ID** (shown as "Your userID for use in API calls").
3. Create a new private key with **read** access to your personal library.
4. Enter the User ID and API key in the app. They are used only to query your library.

## Usage

1. Enter your User ID and API key.
2. Type a search term and tap **SEARCH**.
3. Tick the references you want.
4. Tap **IN-TEXT CITATION**, **NARRATIVE** or **FULL APA 7**. The result is copied to your clipboard.
5. Tap **CLEAR** to reset the list.

## Project structure

| File | Purpose |
|------|---------|
| `app.py` | Kivy user interface |
| `zotero_engine.py` | Zotero API search and APA 7 formatting logic |
| `buildozer.spec` | Android build configuration |
| `.github/workflows/` | GitHub Actions workflow for building the APK |

## Running on a computer

```bash
pip install kivy requests
python app.py
```

## Building the APK

Requires Linux (or WSL) with Buildozer installed:

```bash
pip install buildozer cython
buildozer -v android debug
```

The APK is written to the `bin/` folder.

## Known limitations

- Output is plain text, so APA italics (journal names, book titles) are not preserved
- Titles are copied as stored in Zotero; sentence-case conversion is not applied
- Only the first 25 matching items are shown, with no paging
- Disambiguation of same-author, same-year works (2020a, 2020b) is not implemented
- Credentials must be re-entered each time the app starts

Contributions and bug reports are welcome. Please open an issue.

## License

Released under the [MIT License](LICENSE).
