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
