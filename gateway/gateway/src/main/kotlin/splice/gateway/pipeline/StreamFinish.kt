// NEW: Success-path honesty / promote-to-text / L2 mirror for TurnPipeline
// (concentration, 2026-08-19). Named StreamFinish so it does not collide with
// splice.gateway.head.TurnFinish (the terminal-then-stats collaborator).
package splice.gateway.pipeline

import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.TurnTerminal

internal class StreamFinish(
    compact: StreamCompact,
    log: LogSink,
    private val clampOutput: OutputClamp,
    private val honesty: StreamHonesty,
) {
    private val promote = StreamPromote(compact, log, honesty)

    suspend fun finishSuccess(
        emitter: TurnTerminal,
        outcome: TurnOutcome.Success,
        meta: TurnMeta,
        elapsedMs: Long,
    ): String {
        promote.apply(emitter, outcome, meta, elapsedMs)?.let { return it }

        // Reasoning mirror (L2): one mirrorInto for both paths; tools stay on.
        honesty.mirrorGated(emitter, outcome.thinkingText, meta)

        emitter.emitTerminal(
            hasToolUse = outcome.hasToolUse,
            incomplete = outcome.incomplete,
            usage = Usage(
                outcome.usage.inputTokens,
                clampOutput(outcome.usage.outputTokens),
                outcome.usage.cachedTokens,
            ),
        )
        // DR-87: the collect-path terminal can downgrade this emit into an error envelope
        // (malformed-tool/capacity). A literal "ok" here is what blinded perf/health/log to a
        // turn whose client saw a 502.
        return emitter.degradedReason ?: "ok"
    }
}
