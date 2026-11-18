package org.blaykie.nativeapp.csl

import org.junit.Assert.*
import org.junit.Test

class CslHostStateTest {
    @Test fun renderBeforeReadyAndCancelWarmUp() {
        val machine = CslHostStateMachine()
        assertNull(machine.renderToken())
        val token = requireNotNull(machine.warmUp("style-a"))
        assertNull(machine.renderToken())
        machine.cancel()
        assertEquals(CslHostState.Cancelled, machine.state)
        assertFalse(machine.ready(token))
        assertFalse(machine.fail(token, "late error"))
        assertNull(machine.renderToken())
    }

    @Test fun destroyTwiceIsFinal() {
        val machine = CslHostStateMachine()
        val token = requireNotNull(machine.warmUp("style-a"))
        assertTrue(machine.ready(token))
        assertEquals(token, machine.renderToken())
        machine.destroy(); machine.destroy(); machine.cancel()
        assertEquals(CslHostState.Destroyed, machine.state)
        assertNull(machine.warmUp("style-a"))
        assertNull(machine.renderToken())
        assertFalse(machine.fail(token, "late error"))
    }

    @Test fun failureThenRetryAndStaleCallbacks() {
        val machine = CslHostStateMachine()
        val first = requireNotNull(machine.warmUp("style-a"))
        assertTrue(machine.fail(first, "WebView unavailable"))
        assertEquals(CslHostState.Failed("WebView unavailable"), machine.state)
        val second = requireNotNull(machine.warmUp("style-b"))
        assertFalse(machine.ready(first))
        assertFalse(machine.fail(first, "stale"))
        assertTrue(machine.ready(second))
        assertEquals(CslHostState.Ready("style-b", second), machine.state)
        assertEquals(second, machine.renderToken())
        assertTrue(machine.fail(second, "renderer exited"))
        assertNull(machine.renderToken())
    }

    @Test fun replacementInvalidatesPreviousEngine() {
        val machine = CslHostStateMachine()
        assertNull(machine.warmUp("  "))
        val first = requireNotNull(machine.warmUp("style-a"))
        assertTrue(machine.ready(first))
        val second = requireNotNull(machine.warmUp("style-b"))
        assertFalse(machine.fail(first, "stale"))
        assertNull(machine.renderToken())
        assertTrue(machine.ready(second))
    }
}
