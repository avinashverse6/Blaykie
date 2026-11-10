# F-Droid preparation: inventory and unresolved checks

This is a draft, not a completed F-Droid scanner or license audit. The release build is under `native/`; source-tree files are not necessarily packaged in the release APK.

## Committed binary files

- Gradle wrapper: `native/gradle/wrapper/gradle-wrapper.jar` (prebuilt; wrapper distribution URL and checksum are in `native/gradle/wrapper/gradle-wrapper.properties`).
- Root Kivy fonts: `Poppins-Regular.ttf`, `Poppins-Bold.ttf`.
- Root Kivy images: `icon.png`, `icon_fg.png`, `icon_bg.png`, `presplash.png`.
- Native artwork: `native/app/src/main/res/drawable-nodpi/home_logo.png`, `launcher_background.png`, `launcher_foreground.png`; `native/app/src/main/res/mipmap-mdpi/ic_launcher.png`, `mipmap-hdpi/ic_launcher.png`, `mipmap-xhdpi/ic_launcher.png`, `mipmap-xxhdpi/ic_launcher.png`, `mipmap-xxxhdpi/ic_launcher.png` (all paths under `native/app/src/main/res/`).
- Fastlane artwork: `fastlane/metadata/android/en-US/images/icon.png`.
- Test DOCX fixtures: `native/app/src/test/resources/edit-parity.docx`, `parity.docx`, and `native/app/src/test/resources/m4c/{source,warnings,golden-1_5,golden-double,golden-match,golden-omitted,golden-recovered}.docx`.

## Sources and possible scanner flags

- Dependency repositories for the app are Google Maven and Maven Central. Plugin resolution also includes the **Gradle Plugin Portal**; the checked-in wrapper fetches Gradle 8.9 from `services.gradle.org`. The actual origin of every resolved plugin/transitive dependency has not been audited.
- The checked-in wrapper JAR and other binary files above may require F-Droid review. Debug-only assets include an upstream citeproc-js distribution; its source/build reproducibility has not been established. It and Rhino are excluded from the release APK by CI's isolation check.
- The app has Internet permission and uses Zotero's API. User-directed links open an external browser. No evidence of proprietary app libraries or tracking has been established by this inventory.
- Test DOCX files and root Kivy binaries are in the source tree even though they are not native release assets. Poppins license and root Kivy artwork provenance remain unresolved as described in `THIRD_PARTY_NOTICES.md`.
- `NonFreeNet` should be considered only after checking whether the required Zotero service and its server software meet F-Droid's criteria; use of a network service alone does not prove that anti-feature. No anti-feature is asserted here.
- The exact transitive dependency graph, distribution license texts/NOTICE files, release signature state and Google Docs/Word interoperability still need verification.
