// PORT-OF: the post-machine turn logic from server/src/codex/stream.mjs runStreamTurn tail +
// codex-proxy.mjs handleMessages @ pre-public-port-baseline — invariants: after the stream machine returns its
// TurnOutcome, the gateway (not the provider) runs promote-to-text (only when no text AND no
// tools — compact needs a text channel), the honesty gates (empty compact => api_error, never
// an empty success; completed-but-empty non-compact that the mirror will not cover => api_error),
// the mirror
// (L2, one call), then the SOLE terminal emit. A Failure => emitError; ClientAbandoned =>
// abandon(); a stream that never started + failure still emits an honest error frame.
package splice.gateway.pipeline

import splice.core.turn.ErrorType
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.pickModelText
import splice.gateway.compact.CompactStats
import splice.gateway.reasoning.mirrorInto
import splice.gateway.reasoning.willMirror
import splice.gateway.wire.TurnTerminal

public class TurnPipeline(
    private val compactStats: CompactStats,
    private val log: (String) -> Unit,
    private val clampOutput: (Long) -> Long,
    // Operator knob (mirror_reasoning): false stops the transcript mirror while text-mode
    // display is unaffected. Default true = the measured codex distillation-loop doctrine.
    private val mirrorReasoning: Boolean = true,
) {
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

    private suspend fun finishSuccess(
        emitter: TurnTerminal,
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
            } else if (nothingReachesTheClient(outcome, meta)) {
                emitter.emitError(ErrorType.API_ERROR, "claudex: model returned no content (empty response) — retry")
                return "empty_model"
            }
        } else if (meta.compact && emittedText) {
            recordCompact("model_text", elapsedMs, chars = bodyText.length)
        }

        // Reasoning mirror (L2): one mirrorInto for both paths; tools stay on.
        mirrorGated(emitter, outcome.thinkingText, meta)

        emitter.emitTerminal(
            hasToolUse = outcome.hasToolUse,
            incomplete = outcome.incomplete,
            usage = Usage(
                outcome.usage.inputTokens,
                clampOutput(outcome.usage.outputTokens),
                outcome.usage.cachedTokens,
            ),
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

    // The mirror_reasoning knob gates the CALL — mirrorInto stays the single L2 definition
    // (the ast-grep wall pins it to Mirror.kt); off = no transcript reinjection, display untouched.
    private suspend fun mirrorGated(sink: splice.spi.WireSink, thinkingText: String?, meta: TurnMeta) {
        if (mirrorReasoning) mirrorInto(sink, thinkingText, meta.showReasoning, meta.compact)
    }

    /** CX-09: the empty-turn verdict — nothing reached the client this turn and nothing will.
     *
     *  Both halves matter. [TurnOutcome.Success.emittedThinking] covers what ALREADY went out: the
     *  translators stream native thinking blocks regardless of the mirror knob and regardless of
     *  showReasoning, so a thinking-only turn is not empty just because the text mirror will stay
     *  quiet. [willMirrorHere] covers what still might go out. Only when both are false has the
     *  client genuinely received nothing — which today means the harvest fallback, where the
     *  thinking buffer is refilled from the completed response without the sink ever being
     *  touched. */
    private fun nothingReachesTheClient(outcome: TurnOutcome.Success, meta: TurnMeta): Boolean =
        !outcome.emittedThinking && !willMirrorHere(outcome.thinkingText, meta)

    /** CX-09: the same predicate [mirrorGated] obeys, including the operator knob.
     *
     *  This answers only "will the TEXT MIRROR emit?" — it is NOT the whole question. Translators
     *  emit native thinking blocks independently of both the knob and showReasoning, and on
     *  anthropic-passthrough `showReasoning = THINKING` is set PRECISELY BECAUSE reasoning already
     *  rides the wire natively (PassthroughRequestBuilder: "pick the showReasoning value that makes
     *  mirrorInto a no-op"). Reading that sentinel as "nothing will cover this turn" turned every
     *  thinking-only kimi turn into an API_ERROR — a regression caught in adversarial review before
     *  it shipped. So the honesty gate asks [TurnOutcome.Success.emittedThinking] FIRST, and only
     *  falls back to this predicate when nothing reached the wire at all. */
    private fun willMirrorHere(thinkingText: String?, meta: TurnMeta): Boolean =
        mirrorReasoning && willMirror(thinkingText, meta.showReasoning, meta.compact)
}
