# Third-party notices

This inventory records dependencies explicitly declared in the repository, relevant build tools, and image/font assets. The repository does **not** contain a complete dependency lock or resolved dependency tree: transitive and unpinned packages, their exact installed versions, and their full license/notice obligations must be checked against each distributed build. A name here does not itself satisfy a license's requirements to provide license texts, notices, or source.

## Native Android app: declared runtime dependencies

| Component | Declared version | SPDX license | Project |
|---|---|---|---|
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | https://github.com/androidx/androidx |
| AndroidX Compose UI | 1.7.3 | Apache-2.0 | https://github.com/androidx/androidx |
| AndroidX Compose Material 3 | 1.3.1 | Apache-2.0 | https://github.com/androidx/androidx |
| AndroidX DataStore Preferences | 1.1.1 | Apache-2.0 | https://github.com/androidx/androidx |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| kotlinx.serialization JSON | 1.6.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Kotlin standard library | Transitive, not individually pinned | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Kotlin coroutines | Transitive, not individually pinned | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Okio | Transitive, not individually pinned | Apache-2.0 | https://github.com/square/okio |

AndroidX Core, Lifecycle and other AndroidX/Compose libraries, Kotlin standard library, Kotlin coroutines, and Okio are also used directly or transitively. Their resolved versions are not pinned individually in the checked-in build files; inspect the Gradle runtime dependency graph for exact versions and license/notice information for a particular APK. Do not treat this inventory as a complete transitive SBOM.

### Citation styles: release files

The CSL-enabled native release ships the following separate, unmodified upstream files (snapshot commits are pinned repository snapshots):

| File | Upstream project and URL | Pinned commit | Bytes | SHA-256 | License |
|---|---|---|---:|---|---|
| `citeproc.js` | citeproc-js, Frank Bennett: https://github.com/Juris-M/citeproc-js | `cc9153c45293af878de08cafddbefe6ea150c380` | 970020 | `db98d3341d39eb7a9f166231b6c51118ed499f79821daa3e41f1ea8d7f8d0cbb` | AGPL-3.0-or-later option |
| `locales-en-US.xml` | CSL locales: https://github.com/citation-style-language/locales | `8bef426f89f38c0789223c50e53ec2197bc50bb3` | 32649 | `ac864c7c21166b4390d82c31792cdc509400727fa0060b43d8aa17e07f9cb079` | CC BY-SA 3.0 |

The citeproc-js header is retained untouched. The English locale is a separate unmodified data file; its authors and contributors are listed in the byte-identical file, with their metadata retained. Credit the Citation Style Language project and contributors: https://citationstyles.org/. CC BY-SA 3.0: https://creativecommons.org/licenses/by-sa/3.0/.

Blaykie's own code remains AGPL-3.0-or-later. Styles imported by users stay on their device and are not redistributed by Blaykie. This notice does not replace review of license-text and other distribution obligations.

### Native assets

- Home logo and launcher icon: **original artwork by the author (inspired by the author's own cat), covered by the project license**. These are the supplied full icon and adaptive foreground/background, with density-sized legacy copies derived from the full icon.
- Material Design settings, back and undo vector icons: Apache-2.0; source https://github.com/google/material-design-icons. The repository does not pin an upstream icon revision; see the vector resources for attribution comments.
- The native app uses unmodified static **Lora** Regular and Bold, version 3.008, supplied from Google Fonts (https://fonts.google.com/specimen/Lora; https://github.com/google/fonts). Lora is distributed under the SIL Open Font License 1.1. The supplied OFL text states: `Copyright 2011 The Lora Project Authors (https://github.com/cyrealtype/Lora-Cyrillic), with Reserved Font Name "Lora".` The unmodified license is in `native/LICENSES/Lora-OFL.txt` and shipped as the `lora_ofl` raw resource and shown in About.
  - `lora_regular.ttf`: 133888 bytes; SHA-256 `7ed00e7c9cdf16ab7e2fd2361fe45d4f0b61263cd60aae398b27b7ee08108827`.
  - `lora_bold.ttf`: 133828 bytes; SHA-256 `7d74015e950c2fb66519c7295b8155621d22200ae2ca2a4c6b43ce3c490cac87`.
  - `Lora-OFL.txt` / `lora_ofl.txt`: 4516 bytes each; SHA-256 `41cf75bf229a1435b7df209ac7419622516eff7e8cab79260fb0fb9b1cc8ced2`.
- The native app does not bundle Poppins or Liberation Serif. The repository-root Poppins files remain for the Kivy app; see its inventory below.

### Native test-only dependencies

| Component | Declared version | SPDX license | Project |
|---|---|---|---|
| OkHttp MockWebServer | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| JUnit | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |
| Hamcrest Core (JUnit transitive) | Not directly pinned | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |

JUnit is used for tests, not included as an app runtime dependency. EPL-1.0 is not generally GPL-compatible; do not assume that it can be combined into an AGPL-covered distributed work merely because this project uses it for tests.

### Native build tools

| Component | Declared version | SPDX license | Project |
|---|---|---|---|
| Android Gradle plugin | 8.7.3 | Apache-2.0 | https://android.googlesource.com/platform/tools/base/ |
| Kotlin Android and serialization Gradle plugins | 1.9.25 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Compose compiler extension | 1.5.15 | Apache-2.0 | https://github.com/androidx/androidx |
| Gradle wrapper/distribution | 8.9 | Apache-2.0 | https://github.com/gradle/gradle |
| Android SDK/build tools | SDK API 35; installed build-tools version varies | License varies by component; review the SDK terms | https://developer.android.com/studio |

## Kivy app: declared runtime requirements and assets

Versions are taken from `buildozer.spec`. “Unpinned” means the source configuration does not specify an exact version, not that a particular version is guaranteed to be distributed.

| Component | Declared version | SPDX license | Project |
|---|---|---|---|
| CPython / hostpython3 | 3.11.9 | PSF-2.0 (plus bundled third-party terms) | https://github.com/python/cpython |
| Kivy | 2.3.1 | MIT | https://github.com/kivy/kivy |
| Requests | 2.28.1 | Apache-2.0 | https://github.com/psf/requests |
| urllib3 | 1.26.18 | MIT | https://github.com/urllib3/urllib3 |
| idna | Unpinned | BSD-3-Clause (current upstream) | https://github.com/kjd/idna |
| certifi | Unpinned | MPL-2.0 (current upstream) | https://github.com/certifi/python-certifi |
| androidstorage4kivy | Unpinned | MIT (current upstream) | https://github.com/Android-for-Python/androidstorage4kivy |
| Poppins Regular and Bold (`Poppins-*.ttf`) | Font metadata 4.004 | OFL-1.1 | https://github.com/itfoundry/Poppins |
| Root `icon.png`, `icon_fg.png`, `icon_bg.png`, `presplash.png` | Repository assets | Provenance and applicable license not established here | This repository |

Poppins font metadata credits the Poppins Project Authors, copyright 2020. The Kivy configuration includes `.png` and `.ttf` source assets; inspect the built APK to confirm what was actually included. Python-for-android and its recipes can add further transitive dependencies, including Android bridges and native libraries; their resolved versions and licenses require a build-specific audit.

`python-docx` is used by the repository's Python tests but is **not** declared in `buildozer.spec` and is not established as bundled in the Kivy app. Its upstream license is MIT; its test dependency version is not pinned in this repository. Project: https://github.com/python-openxml/python-docx.

### Additional possible Python transitive dependencies

These are upstream package-metadata requirements or Android bridges, not proof that the python-for-android build packages each one. Versions are not pinned in the repository; confirm the actual build before distribution.

| Component | Version | SPDX license or status | Project |
|---|---|---|---|
| charset-normalizer (Requests) | Unpinned; Requests 2.28.1 specifies >=2,<3 | MIT (current upstream) | https://github.com/jawah/charset_normalizer |
| Kivy-Garden | Unpinned | MIT (current upstream) | https://github.com/kivy-garden/garden |
| Docutils | Unpinned | Multiple licenses/public domain; audit packaged files | https://docutils.sourceforge.io/ |
| Pygments | Unpinned | BSD-2-Clause (current upstream) | https://github.com/pygments/pygments |
| filetype | Unpinned | MIT (current upstream) | https://github.com/h2non/filetype.py |
| Pyjnius | Unpinned | MIT (current upstream) | https://github.com/kivy/pyjnius |
| lxml (python-docx tests) | Unpinned | BSD-3-Clause, plus native-library terms | https://github.com/lxml/lxml |
| typing-extensions (python-docx tests) | Unpinned | PSF-2.0 (current upstream) | https://github.com/python/typing_extensions |

### Kivy build tools

| Component | Declared version | SPDX license | Project |
|---|---|---|---|
| Buildozer | 1.5.0 (CI) | MIT | https://github.com/kivy/buildozer |
| Cython | 0.29.37 (CI) | Apache-2.0 | https://github.com/cython/cython |
| python-for-android | Branch `v2026.05.09` (not a pinned commit) | MIT | https://github.com/kivy/python-for-android |
| pip | 24.0 (CI) | MIT | https://github.com/pypa/pip |

## Compatibility and missing license material

Apache-2.0, MIT, BSD-3-Clause, PSF-2.0, and MPL-2.0 are generally compatible with AGPL-3.0 when their applicable terms are met; MPL-covered files retain their own source and notice obligations. OFL-1.1 permits bundling fonts with software under other licenses, but the fonts themselves must remain under OFL-1.1 and retain the required notices. This is a technical inventory, not legal advice.

Still needed before distribution: exact OFL-1.1 license and copyright notices for the Poppins fonts; Apache-2.0 license text and any applicable upstream NOTICE files for bundled AndroidX/Compose, OkHttp, Kotlin and Material icons; applicable notices and licenses for all resolved transitive dependencies; and any notices/source required for the particular certifi CA bundle and other packaged Kivy components. The checked-in Gradle wrapper JAR is also an Apache-2.0-distributed binary. Verify root Kivy artwork provenance and the licensing of Android SDK components, native libraries, Docutils files and unpinned dependencies. Check the complete resolved APK contents and their exact versions before asserting full compatibility or compliance.
