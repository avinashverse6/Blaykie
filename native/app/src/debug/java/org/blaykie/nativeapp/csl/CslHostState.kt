package org.blaykie.nativeapp.csl

/** Pure lifecycle model. Tokens prevent late WebView callbacks from reviving an old run. */
internal sealed class CslHostState {
    object Idle : CslHostState()
    data class WarmingUp(val styleId: String, val token: Long) : CslHostState()
    data class Ready(val styleId: String, val token: Long) : CslHostState()
    data class Failed(val message: String) : CslHostState()
    object Cancelled : CslHostState()
    object Destroyed : CslHostState()
}

internal class CslHostStateMachine {
    private var generation = 0L
    var state: CslHostState = CslHostState.Idle
        private set

    /** Starting a replacement invalidates all callbacks from the previous engine. */
    @Synchronized fun warmUp(styleId: String): Long? {
        if (state is CslHostState.Destroyed || styleId.isBlank()) return null
        val token = ++generation
        state = CslHostState.WarmingUp(styleId, token)
        return token
    }

    @Synchronized fun ready(token: Long): Boolean {
        val current = state as? CslHostState.WarmingUp ?: return false
        if (current.token != token) return false
        state = CslHostState.Ready(current.styleId, token)
        return true
    }

    @Synchronized fun fail(token: Long, message: String): Boolean {
        val currentToken = when (val current = state) {
            is CslHostState.WarmingUp -> current.token
            is CslHostState.Ready -> current.token
            else -> return false
        }
        if (currentToken != token) return false
        ++generation
        state = CslHostState.Failed(message.ifBlank { "Citation style unavailable." })
        return true
    }

    /** A caller must not issue render commands when no engine is ready. */
    @Synchronized fun renderToken(): Long? = (state as? CslHostState.Ready)?.token

    @Synchronized fun cancel() {
        if (state is CslHostState.Destroyed) return
        ++generation
        state = CslHostState.Cancelled
    }

    @Synchronized fun destroy() {
        if (state is CslHostState.Destroyed) return
        ++generation
        state = CslHostState.Destroyed
    }
}
