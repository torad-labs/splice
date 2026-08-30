// PORT-OF: the post-machine turn logic from server/src/codex/stream.mjs runStreamTurn tail +
// codex-proxy.mjs handleMessages @ pre-public-port-baseline — invariants: after the stream machine returns its
// TurnOutcome, the gateway (not the provider) runs promote-to-text (only when no text AND no
// tools — compact needs a text channel), the honesty gates (empty compact => api_error, never
// an empty success; completed-but-empty non-compact that the mirror will not cover => api_error),
// the mirror
// (L2, one call), then the SOLE terminal emit. A Failure => emitError; ClientAbandoned =>
// abandon(); a stream that never started + failure still emits an honest error frame.
package splice.gateway.pipeline

import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.TurnTerminal

public class TurnPipeline(
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
                if (meta.compact) {
                    compact.recordStreamError(elapsedMs, outcome.type.wireName)
                }
                emitter.emitError(outcome.type, outcome.message)
                return "failure:${outcome.type.wireName}"
            }
            TurnOutcome.ClientAbandoned -> {
                emitter.abandon()
                return "client_abort"
            }
            is TurnOutcome.Success -> return streamFinish.finishSuccess(emitter, outcome, meta, elapsedMs)
        }
    }
}
