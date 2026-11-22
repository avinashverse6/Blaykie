# Blaykie 1.3.0

## What's new

- **Print layout follows the dark theme:** Pages in Print layout now use the app's dark theme, so reading a long draft at night is easier on the eyes. If you prefer the familiar white page, turn on the **Light page** switch.
- **Long paragraphs split across pages:** A long paragraph now flows from one page to the next, the way it does in Word and Google Docs, instead of jumping to a new page as one block. Headings stay with the text that follows them, and a paragraph will not leave a single lonely line behind.
- **Justified text, nothing clipped:** Text set to justified in your document now reaches both margins in Print layout, and lines are no longer cut off at the edges or squeezed together.
- **Headers, footers and page numbers:** Print layout now shows your document's headers and footers, including page numbers such as "Page 3 of 26". Different first pages, odd and even pages, and documents with more than one section are supported, and numbering restarts where your document asks it to.
- **Times New Roman looks closer to Word:** Documents written in Times New Roman are now shown in Tinos, a bundled font with the same letter widths, so line breaks and page breaks land much closer to where Word and Google Docs put them.

## Known limits

- Page breaks in Print layout are close to Word and Google Docs, but not always identical.
- Headers and footers in Print layout are read-only; edit them in your word processor.
- Older citations without a saved session can be removed, but their options cannot be edited.
- Custom CSL citation styles stay experimental and off by default.

## Upgrade note

Install 1.3.0 over 1.2.0 using an APK signed with the same signing key.

## Download

When 1.3.0 is published, download the single universal `Blaykie-v1.3.0.apk` and its matching `Blaykie-v1.3.0.apk.sha256` file from the release page. Keep them in the same folder and verify the download before installing:

```sh
sha256sum --check Blaykie-v1.3.0.apk.sha256
```

The command should report `Blaykie-v1.3.0.apk: OK`.
