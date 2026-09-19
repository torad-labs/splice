// PORT-OF: the post-machine turn logic from server/src/codex/stream.mjs runStreamTurn tail +
// codex-proxy.mjs handleMessages @ pre-public-port-baseline — invariants: after the stream machine returns its
// TurnOutcome, the gateway (not the provider) runs promote-to-text (only when no text AND no
// tools — compact needs a text channel), the honesty gates (empty compact => api_error, never
// an empty success; completed-but-empty non-compact that the mirror will not cover => api_error),
// the mirror
// (L2, one call), then the SOLE terminal emit. A Failure => emitError; ClientAbandoned =>
// abandon(); a stream that never started + failure still emits an honest error frame.
package splice.gateway.pipeline

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.TurnTerminal

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
    private val failures = FailurePresenter()
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
                if (meta.compact) {
                    compact.recordStreamError(meta, elapsedMs, outcome.type.wireName)
                }
                // V4-59: the same seam feeds BOTH endings. The deterministic one is the visible
                // leak — its text block is rendered verbatim into the conversation — but the error
                // event carries the identical raw body, so presenting only one of the two would
                // have left most failures still quoting a vendor's JSON at the client.
                val spoken = failures.spoken(outcome.type, outcome.message)
                if (outcome.deterministic) {
                    emitter.emitExplained(EXPLAINED_PREFIX + spoken, outcome.salvagedUsage)
                } else {
                    // V4-81: the wire type is the EMITTER's decision now (SseEmitter.emitError
                    // holds the pre-content rule and the counter it needs), so the pipeline passes
                    // the failure's own permanence through and no longer snapshots perf at all —
                    // the V4-79 parameter is gone with the rule it fed.
                    emitter.emitError(outcome.type, spoken, permanent = outcome.permanent)
                }
                // V4-67: a tear the gateway converted into an outcome keeps the conn-reset tag it
                // would have carried had it escaped to TurnConnEnd. That tag is the only string in
                // the journal that names this failure class, and the row that made a torn stream
                // continuable is the row that would otherwise have hidden its successor.
                return if (outcome.connReset) CONN_RESET_OUTCOME else OutcomeTags.failure(outcome.type)
            }
            is TurnOutcome.ClientAbandoned -> {
                emitter.abandon()
                return OutcomeTag.CLIENT_ABORT.wire
            }
            is TurnOutcome.Success -> return streamFinish.finishSuccess(emitter, outcome, meta, elapsedMs)
        }
    }
}

/** What a deterministic failure reads as on the client: the proxy speaking, marked as such. The
 *  code that follows it says WHICH splice failure this is; the mark says it is us talking. */
private const val EXPLAINED_PREFIX = "\u26A0 splice "
