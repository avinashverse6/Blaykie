# Spike APA comparison

These are plain-text results for the five fixtures in `CslSpikeTest`, using citeproc-js and the existing Kotlin formatter. Rich formatting (including italics), locators, affixes, disambiguation, and additional names are not compared here. This is a narrow experiment, not an APA parity claim.

| Output | CSL | Kotlin | Difference |
|---|---|---|---|
| Parenthetical cluster | (Author0, 2020; Author1, 2021; Author2, 2022; Author3, 2023; शर्मा, 2020) | Same | None in text |
| Narrative for Author0 | Author0 (2020) | Same | None in text |
| Journal, Author0 | Author0, A. (2020). Example title 1. Example Journal, 2, 10–20. | Same | None in text |
| Book | Author1, A. (2021). Example title 2. Example Press. | Same | None in text |
| Webpage | Author2, A. (2022). Example title 3. https://example.org/page | Same | None in text |
| Report | Author3, A. (2023). Example title 4. Example Press. | Same | None in text |
| Journal, Hindi author | शर्माअनिता. (2020). Example title 5. Example Journal, 2, 10–20. | शर्मा, अ. (2020). Example title 5. Example Journal, 2, 10–20. | Author formatting differs: CSL concatenates supplied family and given names; Kotlin uses family and initial. Neither is asserted to be the culturally correct form. |

Generated in GitHub Actions run 42481879601. Verify every comparison after changing the fixtures, processor, style, locale or formatter.
