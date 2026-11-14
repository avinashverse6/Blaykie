package org.blaykie.nativeapp.citation.style

import org.blaykie.nativeapp.session.Session

/** Tracks every session and preview host so disabling closes all of them. Main-thread use only. */
internal class CustomStylesGate<H>(private val destroy: (H) -> Unit) {
    var enabled: Boolean = false
        private set
    private val hosts = mutableSetOf<H>()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) closeAll()
    }
    fun open(factory: () -> H): H? {
        if (!enabled) return null
        return factory().also { hosts.add(it) }
    }
    fun close(host: H) { if (hosts.remove(host)) destroy(host) }
    fun closeAll() {
        val closing = hosts.toList(); hosts.clear()
        closing.forEach(destroy)
    }
}

/** Do not even evaluate the optional factory while the switch is off. */
internal inline fun <T> whenCustomStylesEnabled(enabled: Boolean, factory: () -> T): T? =
    if (enabled) factory() else null

internal fun builtInSession(session: Session): Session = session.copy(
    debugCitationStyleId = null, debugCitationLanguage = "en-US", debugCitationLanguageExplicit = false,
)
