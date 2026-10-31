// NEW: an upstream refusal of a code-mode source post ends the client's turn as that refusal, never as an overload.
package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.failure.FailureEvidence
import splice.core.failure.LoginOwner
import splice.dialect.responses.failure.ResponsesReplies
import splice.provider.codex.failure.CodexReplies
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.Replies
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamFailed
import java.time.Instant

private const val REFUSAL_STATUS = 400
private const val REFUSAL_LAYERS = 4
private const val REFUSAL_BODY = """{"detail":"Bad Request"}"""

/** The upstream's refusal of the post as it arrived: no stream, a 400, and a body that names nothing. */
private val REFUSED = FailedReply(
    status = REFUSAL_STATUS,
    headers = emptyMap(),
    body = REFUSAL_BODY,
    owner = LoginOwner.SPLICE,
    sent = { _, _ -> false },
    answeredAt = Instant.EPOCH,
)

/**
 * Oct 4, 6:31 AM CT: a code-mode source post the upstream refused with HTTP 400 reached Claude Code as
 * overloaded_error, "upstream source failed". The refusal escaped the source reader as a throwable none of its
 * catches named, so the reader's death handler ended the round as an internal fault, and Claude Code retried the
 * same request as an overload for 36 minutes. A plain turn ends that refusal as claudex's own reader reads it; a
 * code-mode round now hands the refusal to the same ending.
 */
class CodeModeUpstreamRefusalTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `a source post refused before any event ends the turn as that refusal`() = runBlocking {
        val manager = bridge(IncrementalRuntime())
        val post = Refusing(GatedPost(StepSink()), refuseFrom = 1)
        try {
            val ended = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, StepSink(), post)
            assertSame(
                post.refusal,
                (ended as RoundResult.Ended).ending,
                "the turn ends on the upstream's own refusal, status and layers intact",
            )
            assertEquals(1, post.refused, "the refused source is posted once")
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a finished script's continuation refused upstream ends the client's step as that refusal`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val gated = GatedPost(StepSink()).apply {
            wholeOnly = true
            complete.complete(Unit)
        }
        val post = Refusing(gated, refuseFrom = 2)
        try {
            val ended = withTimeout(5_000) {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, StepSink(), post)
            }
            assertSame(post.refusal, (ended as RoundResult.Ended).ending, "ended=$ended")
            assertEquals(1, runtime.starts, "the script ran once, from its whole item")
            assertEquals(1, post.refused, "the refused continuation is posted once")
        } finally {
            manager.onHeadStop()
        }
    }

    /** Posts through [first] until post number [refuseFrom], which the upstream refuses as the retry loop does once
     *  it gives up: an UpstreamFailed with the cause claudex's reader read, the status and the attempts it spent. */
    private class Refusing(private val first: GatedPost, private val refuseFrom: Int) : RedirectableRoundPost {
        val refusal = UpstreamFailed(
            Replies(ResponsesReplies, CodexReplies).http(REFUSED),
            FailureEvidence(ErrorMessage.of(REFUSAL_BODY) ?: REFUSAL_BODY, REFUSAL_STATUS, body = REFUSAL_BODY),
            REFUSAL_LAYERS,
        )
        var refused = 0
        private var posts = 0

        override suspend fun invoke(bodyJson: String): RoundResult = into(bodyJson, StepSink())

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            posts++
            if (posts < refuseFrom) return first.into(bodyJson, sink)
            refused++
            return RoundResult.Ended(refusal)
        }
    }
}
