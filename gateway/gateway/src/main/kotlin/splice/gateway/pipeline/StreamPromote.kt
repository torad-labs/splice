// NEW: Promote-to-text + empty-turn honesty for StreamFinish (concentration,
// 2026-08-19). Same-package; StreamFinish keeps the L2 mirror and the sole
// terminal emit.
package splice.gateway.pipeline

import splice.core.turn.ErrorType
import splice.core.turn.ModelTextPicker
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.wire.TurnTerminal

/** What the promote step decided: [endedTag] when the turn ended here (an error terminal was
 *  emitted), else null and the turn flows on to mirror+terminal, tagged [cleanTag] for the log. */
internal data class PromoteVerdict(val endedTag: String?, val cleanTag: String = "ok")

internal class StreamPromote(
    private val compact: StreamCompact,
    private val log: LogSink,
    private val honesty: StreamHonesty,
) {
    /** Apply promote-to-text / empty-compact / empty-message / CX-09 empty-model. */
    suspend fun apply(
        emitter: TurnTerminal,
        outcome: TurnOutcome.Success,
        meta: TurnMeta,
        elapsedMs: Long,
    ): PromoteVerdict {
        if (outcome.emittedText || outcome.hasToolUse) {
            recordCompactShape(meta, outcome.emittedText, outcome.bodyText, elapsedMs)
            return PromoteVerdict(null)
        }
        // No text AND no tools: promote model thinking to text (compact needs a text channel),
        // else grade the empty.
        val picked = ModelTextPicker.pickModelText(outcome.thinkingText, outcome.bodyText)
        return when {
            picked.text.isNotEmpty() -> {
                log(
                    "[gateway] promote-to-text compact=${meta.compact} " +
                        "source=${picked.source} chars=${picked.text.length}\n",
                )
                emitter.addTextBlock(picked.text)
                if (meta.compact) compact.record(picked.source, elapsedMs, chars = picked.text.length)
                PromoteVerdict(null)
            }
            meta.compact -> {
                // An empty compact is an ERROR, not an empty success (Claude Code would store a
                // blank summary and lose the thread). Never invent locally.
                compact.record("empty_model", elapsedMs, error = "api_error")
                log("[gateway] empty-turn shape compact=true ${outcome.outputShape}\n")
                emitter.emitError(
                    ErrorType.API_ERROR,
                    "claudex: compact returned no content from model — retry (upstream ${outcome.outputShape})",
                )
                PromoteVerdict("empty_compact")
            }
            outcome.messageClosed -> {
                // The model closed a message with nothing in it: a finished answer, not a failure
                // (codex ends the turn here). Ending clean is what stops the client retrying the
                // same request a dozen times; the line keeps the shape so the class stays greppable.
                log("[gateway] empty-message turn compact=false ${outcome.outputShape} — ending clean\n")
                PromoteVerdict(null, cleanTag = "empty_message")
            }
            honesty.nothingReachesTheClient(outcome, meta) -> {
                // Name what the backend actually sent: a reasoning-only round, an item type this
                // dialect never renders, or a genuinely empty output are three different bugs, and
                // the old line made them one grep-proof sentence (Astra, 2026-09-05: eleven identical
                // client retries of one turn, each burning 258k input tokens, with no way to tell).
                log("[gateway] empty-turn shape compact=false ${outcome.outputShape}\n")
                emitter.emitError(
                    ErrorType.API_ERROR,
                    "claudex: model returned no content (empty response) — retry (upstream ${outcome.outputShape})",
                )
                PromoteVerdict("empty_model")
            }
            else -> PromoteVerdict(null)
        }
    }

    /** The compact rows for every shape the promote guard skips: text present (model_text) or —
     *  DR-126 — tool_use with NO text, which fell through both recorders and left the drift
     *  instrument blind for exactly the anomalous class (a compact turn has no tools to call; a
     *  model calling one anyway is the drift worth a row). Recorded, not rewritten: the turn
     *  flows on to mirror+emit either way. */
    private fun recordCompactShape(meta: TurnMeta, emittedText: Boolean, bodyText: String, elapsedMs: Long) {
        if (!meta.compact) return
        if (emittedText) {
            compact.record("model_text", elapsedMs, chars = bodyText.length)
        } else {
            compact.record("tooled_no_text", elapsedMs)
        }
    }
}
