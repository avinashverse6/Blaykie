package org.blaykie.nativeapp.csl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal interface PreviewHost {
    suspend fun render(styleId: String, title: String): StylePreview
    fun close()
}
internal interface PreviewClock {
    suspend fun <T : Any> within(milliseconds: Long, block: suspend () -> T): T?
}
internal object RealPreviewClock : PreviewClock {
    override suspend fun <T : Any> within(milliseconds: Long, block: suspend () -> T): T? = withTimeoutOrNull(milliseconds) { block() }
}
internal sealed class PreviewOutcome {
    data class Success(val preview: StylePreview) : PreviewOutcome()
    object TimedOut : PreviewOutcome()
    object Failed : PreviewOutcome()
    object Cancelled : PreviewOutcome()
}
internal const val PREVIEW_TIMEOUT_MS = 20_000L
internal suspend fun runStylePreview(host: PreviewHost, clock: PreviewClock, styleId: String, title: String): PreviewOutcome = try {
    clock.within(PREVIEW_TIMEOUT_MS) { host.render(styleId, title) }?.let(PreviewOutcome::Success) ?: PreviewOutcome.TimedOut
} catch (_: CancellationException) { PreviewOutcome.Cancelled }
catch (_: Exception) { PreviewOutcome.Failed }
finally { host.close() }

/** Failure and cancellation never change the selected style or active host. */
internal data class PreviewFeedback(val selected: String?, val failed: Set<String>, val message: String)
internal fun previewFeedback(current: PreviewFeedback, id: String, outcome: PreviewOutcome): PreviewFeedback = when (outcome) {
    is PreviewOutcome.Success -> current.copy(failed = current.failed - id, message = "Style imported.")
    PreviewOutcome.TimedOut -> current.copy(failed = current.failed + id, message = "Preview timed out.")
    PreviewOutcome.Failed -> current.copy(failed = current.failed + id, message = "Style could not be loaded.")
    PreviewOutcome.Cancelled -> current.copy(message = "Preview cancelled.")
}
