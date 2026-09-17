// PORT-OF: splice/gateway/head/TurnDriver.kt (ReanchorRunner.finalOutcome, plus the
// failure-continuation and search-continuation decisions inlined in ReanchorRunner.run) @ 86f1411
// — invariants unchanged: the re-anchor runner's per-round continuation gate and the cross-round
// merge, split into their own file (HD-24) so ReanchorRunner.kt stays the loop only.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.spi.ReanchorController
import splice.spi.ReanchorRound
import splice.spi.ToolSearchController

internal class ReanchorContinuation(
    private val toolSearch: ToolSearchController?,
    private val signals: RunnerSignals,
    private val rounds: RoundSplice,
) {
    /** Whether a Failure is continuable via re-anchor: the reanchor controller is consulted
     *  whenever the client is still listening.
     *
     *  DR-7: the watchdog half of this gate is gone. "A watchdog fire never continues; its
     *  cancellation owns the turn from that point" was true of a watchdog that cancelled the whole
     *  TURN — it now cancels one ROUND, so the sentence no longer describes the machine. Keeping
     *  the veto made a stalled round unsalvageable no matter what it carried. clientGone stays: a
     *  continuation for a client that hung up is upstream spend with no reader. */
    fun continuationForFailure(
        reanchor: ReanchorController?,
        outcome: TurnOutcome,
        body: JsonObject,
        attempt: Int,
    ): JsonObject? =
        (outcome as? TurnOutcome.Failure)
            ?.takeIf { !signals.clientGone() }
            ?.let { reanchor?.continuationForFailure(ReanchorRound(body, it, attempt)) }

    fun searchContinuation(outcome: TurnOutcome, body: JsonObject, searchIndex: Int): JsonObject? =
        rounds.searchContinuation(toolSearch, outcome, body, searchIndex, signals)

    /** V4-76: THE TOOL-CUT SALVAGE — a stream cut AFTER a COMPLETED tool call ends the turn CLEAN.
     *
     *  Why it must be splice that salvages: once any content block has reached the client, Claude
     *  Code 2.1.257 never retries — it finalizes the partial and prints "Connection lost
     *  mid-response" for a torn stream (measured 2026-09-17 04:24:07 on the operator's own session).
     *  And no continuation is possible for this shape by design: a prefill cannot resume past a tool
     *  call whose result the model has not seen, so the re-anchor gate refuses it — correctly.
     *
     *  But the refusal was about the PREFILL, and the turn was left as a Failure. When the tear came
     *  after a tool_use block COMPLETED, its content_block_stop has already reached the client (the
     *  translators closeAll before the terminal decision — PartialRound's own contract), so the
     *  client is holding a well-formed tool call and can simply RUN it: the model continues on the
     *  next turn from the tool result. Ending at stop_reason tool_use loses nothing, duplicates
     *  nothing and invents nothing.
     *
     *  [toolTearOpen] is the line, and it is the whole line: an OPEN tool tear means the client holds
     *  HALF a tool call that nothing can complete, and it keeps today's honest error.
     *
     *  Usage: the final round's partial was never folded into [salvaged] (the loop folds a partial
     *  only after a continuation is found, and this path is the one where none was), so it is folded
     *  here with acc.plusTerminal — the same idiom withFailureSalvage uses for a terminal round —
     *  which is what keeps the absorbed rounds BILLED rather than silently dropped. */
    fun toolCutSalvage(
        outcome: TurnOutcome,
        salvaged: List<TurnOutcome.PartialRound>,
        acc: RoundUsage,
    ): TurnOutcome.Success? {
        val cut = (outcome as? TurnOutcome.Failure)?.partial ?: return null
        if (!cut.hasToolUse || cut.toolTearOpen) return null
        val total = acc.plusTerminal(cut.usage)
        val clean = TurnOutcome.Success(
            hasToolUse = true,
            incomplete = false,
            usage = total.toUsage(),
        )
        return rounds.mergedAcrossRounds(clean, salvaged + cut) as? TurnOutcome.Success
    }

    /** Cross-round merge: a spliced turn's Success must carry the WHOLE turn's facts (see
     *  RoundSplice.mergedAcrossRounds). Usage is folded in here from the running accumulator. */
    fun finalOutcome(
        outcome: TurnOutcome,
        salvaged: List<TurnOutcome.PartialRound>,
        acc: RoundUsage,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Success || salvaged.isEmpty()) return outcome
        return rounds.mergedAcrossRounds(outcome.copy(usage = acc.plusRound(outcome.usage).toUsage()), salvaged)
    }
}
