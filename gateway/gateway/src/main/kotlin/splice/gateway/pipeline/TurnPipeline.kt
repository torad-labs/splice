// PORT-OF: the post-machine turn logic from server/src/codex/stream.mjs runStreamTurn tail +
// codex-proxy.mjs handleMessages @ 4ca99f7 — invariants: after the stream machine returns its
// TurnOutcome, the gateway (not the provider) runs promote-to-text (only when no text AND no
// tools — compact needs a text channel), the honesty gates (empty compact => api_error, never
// an empty success; completed-but-empty non-compact under HONESTY_MIN => api_error), the mirror
// (L2, one call), then the SOLE terminal emit. A Failure => emitError; ClientAbandoned =>
// abandon(); a stream that never started + failure still emits an honest error frame.
package splice.gateway.pipeline

import splice.core.turn.ErrorType
import splice.core.turn.HONESTY_MIN_CHARS
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.pickModelText
import splice.gateway.compact.CompactStats
import splice.gateway.reasoning.mirrorInto
import splice.gateway.wire.SseEmitter

public class TurnPipeline(
    private val compactStats: CompactStats,
    private val log: (String) -> Unit,
    private val clampOutput: (Long) -> Long,
) {
    /**
     * Finish a streamed turn: apply promote/honesty/mirror to the machine's outcome and drive
     * the emitter to its SOLE terminal. Returns a short outcome tag for the debug log.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod") // the honesty cascade is the ported contract
    public suspend fun finishStream(
        emitter: SseEmitter,
        outcome: TurnOutcome,
        meta: TurnMeta,
        elapsedMs: Long,
    ): String {
        when (outcome) {
            is TurnOutcome.Failure -> {
                if (meta.compact) {
                    recordCompact("stream_error", elapsedMs, error = outcome.type.wireName)
                }
                emitter.emitError(outcome.type, outcome.message)
                return "failure:${outcome.type.wireName}"
            }
            TurnOutcome.ClientAbandoned -> {
                emitter.abandon()
                return "client_abort"
            }
            is TurnOutcome.Success -> return finishSuccess(emitter, outcome, meta, elapsedMs)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun finishSuccess(
        emitter: SseEmitter,
        outcome: TurnOutcome.Success,
        meta: TurnMeta,
        elapsedMs: Long,
    ): String {
        var emittedText = outcome.emittedText
        var bodyText = outcome.bodyText

        // Promote model thinking → text when no text AND no tools (compact needs a text channel).
        if (!emittedText && !outcome.hasToolUse) {
            val picked = pickModelText(outcome.thinkingText, outcome.bodyText)
            if (picked.text.isNotEmpty()) {
                log(
                    "[gateway] promote-to-text compact=${meta.compact} " +
                        "source=${picked.source} chars=${picked.text.length}\n",
                )
                emitter.addTextBlock(picked.text)
                emittedText = true
                bodyText += picked.text
                if (meta.compact) recordCompact(picked.source, elapsedMs, chars = picked.text.length)
            } else if (meta.compact) {
                // An empty compact is an ERROR, not an empty success (Claude Code would store a
                // blank summary and lose the thread). Never invent locally.
                recordCompact("empty_model", elapsedMs, error = "api_error")
                emitter.emitError(ErrorType.API_ERROR, "claudex: compact returned no content from model — retry")
                return "empty_compact"
            } else if (outcome.thinkingText.trim().length < HONESTY_MIN_CHARS) {
                emitter.emitError(ErrorType.API_ERROR, "claudex: model returned no content (empty response) — retry")
                return "empty_model"
            }
        } else if (meta.compact && emittedText) {
            recordCompact("model_text", elapsedMs, chars = bodyText.length)
        }

        // Reasoning mirror (L2): one mirrorInto for both paths; tools stay on.
        mirrorInto(emitter, outcome.thinkingText, meta.showReasoning, meta.compact)

        emitter.emitTerminal(
            hasToolUse = outcome.hasToolUse,
            incomplete = outcome.incomplete,
            usage = Usage(outcome.usage.inputTokens, clampOutput(outcome.usage.outputTokens)),
        )
        return "ok"
    }

    private fun recordCompact(outcome: String, elapsedMs: Long, chars: Int? = null, error: String? = null) {
        compactStats.record(
            buildMap {
                put("outcome", outcome)
                put("ms", elapsedMs)
                chars?.let { put("chars", it) }
                error?.let { put("error", it) }
            },
        )
    }
}
