// NEW: an upstream refusal of a code-mode source post ends the client's turn as that refusal, never as an overload.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamFailed

private const val REFUSAL_STATUS = 400
private const val REFUSAL_LAYERS = 4

/**
 * Oct 4, 6:31 AM CT: a code-mode source post the upstream refused with HTTP 400 reached Claude Code as
 * overloaded_error, "upstream source failed". The refusal escaped the source reader as a throwable none of its
 * catches named, so the reader's death handler ended the round as an internal fault, and Claude Code retried the
 * same request as an overload for 36 minutes. A plain turn ends that refusal through its classifier, which can
 * tell the client to compact; a code-mode round now hands the refusal to the same ending.
 */
class CodeModeUpstreamRefusalTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `a source post refused before any event ends the turn as that refusal`() = runBlocking {
        val manager = bridge(IncrementalRuntime())
        val post = Refusing(GatedPost(StepSink()), refuseFrom = 1)
        try {
            val thrown = assertThrows<UpstreamFailed> {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, StepSink(), post)
            }
            assertSame(post.refusal, thrown, "the turn ends on the upstream's own refusal, status and layers intact")
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
        val sinks = List(3) { StepSink() }
        val itemCompletion = CompletableDeferred<Unit>()
        val gated = GatedPost(sinks.first()).apply { itemCompletionGate = itemCompletion }
        val post = Refusing(gated, refuseFrom = 2)
        val callbacks = mutableListOf<SeenTool>()
        try {
            for (step in sinks.indices) {
                val results = callbacks.mapIndexed { index, call -> CodeModeResult(call.id, "result-$index") }
                val request = async {
                    manager.interceptor(turn(results = results), disableParallel = false)
                        .intercept(history(callbacks), sinks[step], post)
                }
                if (step > 0) gated.gates[step].complete(Unit)
                callbacks += withTimeout(1_500) { sinks[step].callback.await() }
                val outcome = withTimeout(1_500) { request.await() }
                assertTrue(outcome is TurnOutcome.Success, "step $step: $outcome")
            }
            val final = async {
                runCatching {
                    manager.interceptor(
                        turn(
                            results = callbacks.mapIndexed { index, call -> CodeModeResult(call.id, "result-$index") },
                        ),
                        disableParallel = false,
                    ).intercept(history(callbacks), StepSink(), post)
                }
            }
            itemCompletion.complete(Unit)
            gated.complete.complete(Unit)
            val ended = withTimeout(5_000) { final.await() }
            assertSame(post.refusal, ended.exceptionOrNull(), "ended=$ended")
            assertEquals(1, post.refused, "the refused continuation is posted once")
        } finally {
            manager.onHeadStop()
        }
    }

    /** Posts through [first] until post number [refuseFrom], which the upstream refuses as the retry loop does once
     *  it gives up: an UpstreamFailed with the status and the attempts it spent. */
    private class Refusing(private val first: GatedPost, private val refuseFrom: Int) : RedirectableRoundPost {
        val refusal = UpstreamFailed("""{"detail":"Bad Request"}""", REFUSAL_STATUS, REFUSAL_LAYERS)
        var refused = 0
        private var posts = 0

        override suspend fun invoke(bodyJson: String): TurnOutcome = into(bodyJson, StepSink())

        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
            posts++
            if (posts < refuseFrom) return first.into(bodyJson, sink)
            refused++
            throw refusal
        }
    }
}
