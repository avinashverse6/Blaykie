package org.blaykie.nativeapp

/** Bridges immutable settings snapshots to their storage callbacks. */
internal class OptionalSettingsBindings(
    val styleId: String?,
    val languageTag: String?,
    private val writeStyle: (String?) -> Unit,
    private val writeLanguage: (String?) -> Unit,
) {
    fun chooseStyle(value: String?) = writeStyle(value)
    fun chooseLanguage(value: String?) = writeLanguage(value)
}
