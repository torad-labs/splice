// PORT-OF: the post-machine turn logic from server/src/codex/stream.mjs runStreamTurn tail +
// codex-proxy.mjs handleMessages @ pre-public-port-baseline — invariants: after the stream machine returns its
// TurnOutcome, the gateway (not the provider) runs promote-to-text (only when no text AND no
// tools — compact needs a text channel), the honesty gates (empty compact => api_error, never
// an empty success; completed-but-empty non-compact that the mirror will not cover => api_error),
// the mirror
// (L2, one call), then the SOLE terminal emit. A Failure => emitError; ClientAbandoned =>
// abandon(); a stream that never started + failure still emits an honest error frame.
package splice.head.pipeline

import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.head.compact.CompactStats
import splice.head.usage.OutputClamp
import splice.head.wire.TurnTerminal

internal class TurnPipeline(
    compactStats: CompactStats,
    log: LogSink,
    clampOutput: OutputClamp,
    // Operator-locked off: provider-native reasoning display remains, transcript mirroring does not.
    mirrorReasoning: Boolean = false,
) {
    init {
        require(!mirrorReasoning) { "mirrorReasoning is operator-locked off" }
    }

    // Success-path honesty / promote / mirror live in StreamFinish.kt (concentration, 2026-08-19).
    private val compact = StreamCompact(compactStats)
    private val streamFinish = StreamFinish(
        compact,
        log,
        clampOutput,
        StreamHonesty(mirrorReasoning),
    )

    /**
     * Finish a streamed turn: apply promote/honesty/mirror to the machine's outcome and drive
     * the emitter to its SOLE terminal. Returns a short outcome tag for the debug log.
     */
    public suspend fun finishStream(
        emitter: TurnTerminal,
        outcome: TurnOutcome,
        meta: TurnMeta,
        elapsedMs: Long,
    ): String {
        when (outcome) {
            is TurnOutcome.Failure -> {
                val cause = outcome.cause
                if (meta.compact) {
                    compact.recordStreamError(meta, elapsedMs, ClientWire.ownType(cause).wireName)
                }
                // Failures belong to the error channel, including refusals and failures after
                // partial output. A text block and a clean stop would record them as an answer.
                // The emitter asks ClientWire what the client reads, from the cause and how far the
                // stream had got, and its turn voice says the line for the same cause (slice 2).
                emitter.emitError(cause)
                // V4-67: a path that could not be reached, ended as an outcome, keeps the conn-reset
                // tag it would have carried had it escaped to TurnConnEnd. A stream torn before the
                // client saw a frame is no longer one: since failure-handling slice 4 it is read as
                // the post's own loop reads it, a torn stream (SseRoundDriver.tearOutcome), and its
                // row names it so (MidStreamTearContinuesTest, ARM 7).
                return if (cause is FailureCause.Path.Unreachable) CONN_RESET_OUTCOME else OutcomeTags.failure(cause)
            }
            is TurnOutcome.ClientAbandoned -> {
                emitter.abandon()
                return OutcomeTag.CLIENT_ABORT.wire
            }
            is TurnOutcome.Success -> return streamFinish.finishSuccess(emitter, outcome, meta, elapsedMs)
        }
    }
}
