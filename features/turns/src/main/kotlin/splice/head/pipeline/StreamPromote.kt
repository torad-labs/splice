// NEW: Promote-to-text + empty-turn honesty for StreamFinish (concentration,
// 2026-08-19). Same-package; StreamFinish keeps the L2 mirror and the sole
// terminal emit.
package splice.head.pipeline

import splice.core.failure.FailureCause
import splice.core.perf.OutcomeTag
import splice.core.turn.ModelTextPicker
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.head.wire.TurnTerminal

/** What the promote step decided: [endedTag] when the turn ended here (an error terminal was
 *  emitted), else null and the turn flows on to mirror+terminal, tagged [cleanTag] for the log. */
internal data class PromoteVerdict(val endedTag: String?, val cleanTag: String = OutcomeTag.OK.wire)

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
        if (outcome.text.emittedText || outcome.hasToolUse) {
            recordCompactShape(meta, outcome.text.emittedText, outcome.text.bodyText, elapsedMs)
            return PromoteVerdict(null)
        }
        // No text AND no tools: promote model thinking to text (compact needs a text channel),
        // else grade the empty.
        val picked = ModelTextPicker.pickModelText(outcome.text.thinkingText, outcome.text.bodyText)
        return when {
            picked.text.isNotEmpty() -> {
                log(
                    "[gateway] promote-to-text compact=${meta.compact} " +
                        "source=${picked.source} chars=${picked.text.length}\n",
                )
                emitter.addTextBlock(picked.text)
                if (meta.compact) compact.record(meta, picked.source, elapsedMs, chars = picked.text.length)
                PromoteVerdict(null)
            }
            meta.compact -> {
                // An empty compact is an ERROR, not an empty success (Claude Code would store a
                // blank summary and lose the thread). Never invent locally.
                compact.record(meta, OutcomeTag.EMPTY_MODEL.wire, elapsedMs, error = "api_error")
                log("[gateway] empty-turn shape compact=true ${outcome.shape.outputShape}\n")
                // V4-42 (operator law, 2026-09-17: retry on every error, never stall): OVERLOADED,
                // the same retryable wire type as the empty_model branch below. A compaction that
                // ends terminally leaves the session growing until it dies, which is the stall
                // this ending exists to prevent; the client's backoff bounds the re-sends.
                emitter.emitError(FailureCause.Provider.Empty(reasoningOnly = outcome.shape.endedInReasoning))
                PromoteVerdict(OutcomeTag.EMPTY_COMPACT.wire)
            }
            finishedEmpty(outcome, meta) -> {
                // The model closed a message with nothing in it: a finished answer, not a failure
                // (codex ends the turn here). Ending clean is what stops the client retrying the
                // same request a dozen times; the line keeps the shape so the class stays greppable.
                // The same ending covers a round that used all of the client's max_tokens before a block
                // opened (see finishedEmpty); its stop_reason=max_tokens reaches the client as the upstream said it.
                log("[gateway] empty-message turn compact=false ${outcome.shape.outputShape}; ending clean\n")
                PromoteVerdict(null, cleanTag = OutcomeTag.EMPTY_MESSAGE.wire)
            }
            honesty.nothingReachesTheClient(outcome, meta) -> {
                // Name what the backend actually sent: a reasoning-only round, an item type this
                // dialect never renders, or a genuinely empty output are three different bugs, and
                // the old line made them one grep-proof sentence (Astra, 2026-09-05: eleven identical
                // client retries of one turn, each burning 258k input tokens, with no way to tell).
                log("[gateway] empty-turn shape compact=false ${outcome.shape.outputShape}\n")
                // V4-42: OVERLOADED, not API_ERROR. Zero content means NOTHING has reached the
                // client, so the turn is indistinguishable from a transient overload and there is
                // no client-visible work to lose — but the type decides whether the client recovers.
                // Before content, Claude Code re-sends an api_error IDENTICALLY until it gives up
                // (the 2.1.x behaviour recorded on TurnOutcome.Failure.deterministic: 87 and 47
                // identical turns on 2026-09-07), which reproduces the same empty turn instead of
                // recovering from it; overloaded_error is retried on a backoff. The upstream shape, which is the
                // diagnosis, stays in the log line above; the client reads the line for an empty answer.
                emitter.emitError(FailureCause.Provider.Empty(reasoningOnly = outcome.shape.endedInReasoning))
                PromoteVerdict(OutcomeTag.EMPTY_MODEL.wire)
            }
            else -> PromoteVerdict(null)
        }
    }

    /** A finished answer that holds nothing: the model closed an empty message, or the upstream stopped on max_tokens
     *  having produced as many tokens as the client allowed. The second is the answer to a request whose cap left room
     *  for nothing, such as Claude Code's one-token model check behind `/model <full id>`, which got a 529 here and
     *  kept the old model (the-temple, 2026-10-19). Re-sending the same body spends the same cap. A max_tokens stop with
     *  tokens to spare stays the empty error. */
    private fun finishedEmpty(outcome: TurnOutcome.Success, meta: TurnMeta): Boolean {
        val cap = meta.route.clientMaxTokens
        val spentCap = cap != null && outcome.incomplete && outcome.usage.outputTokens >= cap
        return outcome.shape.messageClosed || spentCap
    }

    /** The compact rows for every shape the promote guard skips: text present (model_text) or —
     *  DR-126 — tool_use with NO text, which fell through both recorders and left the drift
     *  instrument blind for exactly the anomalous class (a compact turn has no tools to call; a
     *  model calling one anyway is the drift worth a row). Recorded, not rewritten: the turn
     *  flows on to mirror+emit either way. */
    private fun recordCompactShape(meta: TurnMeta, emittedText: Boolean, bodyText: String, elapsedMs: Long) {
        if (!meta.compact) return
        if (emittedText) {
            compact.record(meta, "model_text", elapsedMs, chars = bodyText.length)
        } else {
            compact.record(meta, "tooled_no_text", elapsedMs)
        }
    }
}
