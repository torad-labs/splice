// PORT-OF: splice/gateway/head/TurnDriver.kt (RoundSplice) @ 86f1411 — invariants unchanged: the
// v29 law, one definition of searchContinuation/bufferedForSearch/searchPartial/
// withFailureSalvage/mergedAcrossRounds shared by both runners.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.spi.ToolSearchController
import splice.spi.ToolSearchRound

/** The round-splicing laws BOTH runners obey (never two copies — the v29 law). Each runner holds
 *  its own `private val rounds = RoundSplice()`; the bodies below are the single definition. */
internal class RoundSplice {
    /** The search-continuation gate, shared by both runners (never two copies — the v29 law). A
     *  search NEVER continues past a watchdog fire or a dead client (the same rule re-anchor
     *  applies), and never past a round that already committed a real tool_use to the client's wire
     *  — that check lives inside ResponsesToolSearchController itself (TurnOutcome.Success.hasToolUse),
     *  so it need not be repeated here. */
    fun searchContinuation(
        search: ToolSearchController?,
        outcome: TurnOutcome,
        body: JsonObject,
        roundIndex: Int,
        signals: RunnerSignals,
    ): JsonObject? {
        if (search == null || outcome !is TurnOutcome.Success) return null
        if (signals.watchdogFired() || signals.clientGone()) return null
        return search.continuationForSearch(ToolSearchRound(body, outcome, roundIndex))
    }

    /** FoldRunner's rounds run through a BufferingWireSink: [TurnOutcome.Success.emittedText]/
     *  [TurnOutcome.Success.bodyText] reflect what the round PRODUCED, not what reached the client.
     *  Strips both before a search continuation can replay them — the same rule FoldRunner's own
     *  re-anchor branch (trigger B) applies to its salvage. A no-op for ReanchorRunner's LIVE rounds
     *  (non-Success outcomes pass through; searchContinuation's own type guard ignores them anyway). */
    fun bufferedForSearch(outcome: TurnOutcome): TurnOutcome =
        if (outcome is TurnOutcome.Success) outcome.copy(bodyText = "", emittedText = false) else outcome
    // CX-09 note: emittedThinking is deliberately NOT stripped here — BufferingWireSink buffers only
    // text/tool ops and forwards openThinking/thinkingDelta straight through, so a reasoning block from
    // a buffered round DID reach the client and must keep the honesty gate quiet.

    /** The search-round salvage entry, shared by both runners so they cannot drift (the v29 law).
     *  [buffered]=true (FoldRunner) means the round's output never reached the client — text signals
     *  are stripped so a discarded round cannot vouch for content in the merge (the same rule the
     *  fold re-anchor branch applies just above). [buffered]=false (ReanchorRunner) carries the
     *  round's REAL emitted text truthfully — it already reached the wire. usage is zeroed on both;
     *  the caller already folded it into acc. hasToolUse is always false: [searchContinuation] never
     *  fires on a round that carried one. */
    fun searchPartial(success: TurnOutcome.Success, buffered: Boolean): TurnOutcome.PartialRound =
        if (buffered) {
            TurnOutcome.PartialRound(
                thinkingText = success.thinkingText,
                bodyText = "",
                emittedText = false,
                // CX-09: reasoning is NOT buffered (BufferingWireSink forwards openThinking/
                // thinkingDelta to the real sink), so it genuinely reached the client — the same
                // reason thinkingText itself survives this strip.
                emittedThinking = success.emittedThinking,
            )
        } else {
            TurnOutcome.PartialRound(
                thinkingText = success.thinkingText,
                bodyText = success.bodyText,
                emittedText = success.emittedText,
                emittedThinking = success.emittedThinking,
            )
        }

    /** A turn that absorbed re-anchor rounds and STILL failed burned real billed tokens on those
     *  rounds; carry them on the Failure so finishTurn can account them (review-pr 2026-07-24 —
     *  before re-anchoring a Failure was always single-round, so there was nothing to lose).
     *  DR-124: the TERMINAL round's own burn (Failure.partial.usage — the harvest
     *  ResponsesEventReducer takes from response.failed precisely so this accounting is real)
     *  folds in too; dropping it under-counted multi-round turns by exactly their heaviest round
     *  and stamped nothing on a single-round failure with reported usage. */
    fun withFailureSalvage(outcome: TurnOutcome, acc: RoundUsage): TurnOutcome {
        if (outcome !is TurnOutcome.Failure) return outcome
        val total = outcome.partial?.usage?.let { acc.plusTerminal(it) } ?: acc
        val nothingBurned = total.outSum + total.reasoningSum <= 0 && total.lastInput <= 0
        if (nothingBurned) return outcome
        return outcome.copy(salvagedUsage = total.toUsage())
    }

    /** Cross-round merge (code-review 2026-07-24): the post-stream pipeline — empty-model honesty
     *  gate, promote-to-text, reasoning mirror — is round-blind; it sees ONE outcome. A spliced
     *  turn's Success must carry the WHOLE turn's facts, or a round-2 empty completion after a lost
     *  terminal frame turns an already-delivered answer into a client-visible error, and earlier
     *  rounds' reasoning vanishes from the mirror. Usage on [outcome] must already be round-summed. */
    fun mergedAcrossRounds(
        outcome: TurnOutcome,
        salvaged: List<TurnOutcome.PartialRound>,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Success || salvaged.isEmpty()) return outcome
        return outcome.copy(
            hasToolUse = outcome.hasToolUse || salvaged.any { it.hasToolUse },
            emittedText = outcome.emittedText || salvaged.any { it.emittedText },
            emittedThinking = outcome.emittedThinking || salvaged.any { it.emittedThinking },
            thinkingText = (salvaged.map { it.thinkingText } + outcome.thinkingText)
                .filter { it.isNotEmpty() }
                .joinToString("\n\n"),
            bodyText = (salvaged.map { it.bodyText } + outcome.bodyText).joinToString(""),
        )
    }
}
