package org.blaykie.nativeapp.csl

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Test

class PreviewRunnerTest {
    private class FakeClock : PreviewClock {
        private val alarm = CompletableDeferred<Unit>()
        var requested = 0L
        private var now = 0L
        fun advanceBy(ms: Long) { now += ms; if (now >= requested && requested > 0) alarm.complete(Unit) }
        override suspend fun <T : Any> within(milliseconds: Long, block: suspend () -> T): T? = coroutineScope {
            requested = milliseconds
            val work = async { block() }
            select<T?> {
                work.onAwait { it }
                alarm.onAwait { work.cancel(); null }
            }
        }
    }
    private class FakeHost(private val action: suspend () -> StylePreview) : PreviewHost {
        var calls = 0; var closed = 0
        override suspend fun render(styleId: String, title: String): StylePreview { calls++; return action() }
        override fun close() { closed++ }
    }
    private val initial = PreviewFeedback("active-style", setOf("existing-failure"), "")
    private fun assertSafe(outcome: PreviewOutcome, host: FakeHost, message: String, failed: Boolean) {
        val feedback = previewFeedback(initial, "candidate", outcome)
        assertEquals("active-style", feedback.selected)
        assertEquals(failed, "candidate" in feedback.failed)
        assertTrue("existing-failure" in feedback.failed)
        assertEquals(message, feedback.message)
        assertFalse(feedback.message.contains("SECRET")); assertFalse(feedback.message.contains('\n'))
        assertEquals(1, host.closed)
    }
    @Test fun timeoutAfterExactlyTwentySecondsDoesNotTouchActiveHost() = runBlocking {
        val clock = FakeClock(); val candidate = FakeHost { awaitCancellation() }
        val activeHost = FakeHost { error("active host must not be used") }
        val task = async { runStylePreview(candidate, clock, "candidate", "Title") }
        yield(); assertEquals(20_000L, clock.requested)
        clock.advanceBy(19_999); yield(); assertFalse(task.isCompleted)
        clock.advanceBy(1); val outcome = task.await()
        assertEquals(PreviewOutcome.TimedOut, outcome)
        assertSafe(outcome, candidate, "Preview timed out.", true)
        assertEquals(0, activeHost.calls); assertEquals(0, activeHost.closed)
    }
    @Test fun renderFailureIsSanitizedAndLeavesSelectedStyleAndHostAlone() = runBlocking {
        val candidate = FakeHost { error("SECRET file content\nsecond line") }
        val active = FakeHost { error("must not run") }
        val outcome = runStylePreview(candidate, FakeClock(), "candidate", "Title")
        assertEquals(PreviewOutcome.Failed, outcome)
        assertSafe(outcome, candidate, "Style could not be loaded.", true)
        assertEquals(0, active.calls); assertEquals(0, active.closed)
    }
    @Test fun cancelledPreviewClosesOnlyItsOwnHostAndShowsMessage() = runBlocking {
        val candidate = FakeHost { awaitCancellation() }; val active = FakeHost { error("must not run") }
        val clock = FakeClock(); var result: PreviewOutcome? = null
        val job = launch { result = runStylePreview(candidate, clock, "candidate", "Title") }
        yield(); job.cancelAndJoin()
        assertEquals(PreviewOutcome.Cancelled, result)
        assertSafe(requireNotNull(result), candidate, "Preview cancelled.", false)
        assertEquals(0, active.calls); assertEquals(0, active.closed)
    }
    @Test fun failureOfTheSelectedStyleStillLeavesSelectionUnchanged() {
        val feedback = previewFeedback(initial.copy(selected = "candidate"), "candidate", PreviewOutcome.Failed)
        assertEquals("candidate", feedback.selected); assertTrue("candidate" in feedback.failed)
    }
}
