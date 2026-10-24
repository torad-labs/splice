// NEW: a 4xx for a body the WebSocket peer already refused as too large is that refusal again, read as an overflow.
package splice.upstream.failure

import splice.core.turn.FailureCause
import splice.core.wire.HttpStatus
import splice.upstream.transport.PostContext
import splice.upstream.transport.RetryOutcome

/**
 * The evidence a bare refusal cannot carry. The Responses WebSocket peer closes a round's socket with 1009 (RFC
 * 6455: message too big) before its first event when the request frame is over its limit, and the round then
 * rides SSE with the same body. On Oct 4 all 42 such rounds drew HTTP 400 `{"detail":"Bad Request"}` there, a
 * body that names nothing, so the retry loop sent each four times and the client read an overload.
 *
 * After the 1009 that 4xx is the second refusal of the same bytes. It is read here as a context overflow, which
 * RetryRules never re-sends and the turn head returns as HTTP 400 "prompt is too long", the line Claude Code
 * compacts on. A 429 and a 5xx say nothing about the body and keep their own paths, and an upstream that named
 * its own overflow keeps its words.
 */
internal object WsSizeRefusal {
    fun read(ctx: PostContext, failed: RetryOutcome.Failed): RetryOutcome.Failed {
        val sizeRefusal = ctx.bodyRefusedAsTooLarge &&
            failed.status in HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR &&
            failed.status != HttpStatus.TOO_MANY_REQUESTS
        if (!sizeRefusal) return failed
        val own = UpstreamFailureClassifier.classify(FailureSource.HTTP, failed.text, failed.status)
        if (own.cause == FailureCause.REQUEST_TOO_LARGE) return failed
        return failed.copy(text = envelope(failed.status))
    }

    // The upstream's own body named nothing. The sentence states both refusals and carries no "N tokens > M",
    // which Claude Code would parse as a token gap this proxy never measured.
    private fun envelope(status: Int): String =
        """{"error":{"type":"invalid_request_error","message":"prompt is too long: the upstream refused this """ +
            """request as too large. Its WebSocket closed with 1009 (message too big), and HTTP answered """ +
            """$status for the same body."}}"""
}
