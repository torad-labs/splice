// PORT-OF: splice/gateway/head/TurnDriver.kt (ReanchorRunner.run, the class shell) @ 86f1411 —
// invariants unchanged: mid-stream re-anchoring loop (eli design 2026-07-24), the LIVE-emitter
// counterpart of [FoldRunner], split from splice.gateway.head (HD-24, the round subsystem's own
// package). Rounds drive the real wire directly — committed blocks stay; a round that fails with a
// continuable partial re-POSTs the continuation and APPENDS; everything else finishes with the
// round's honest outcome. The emitter's seal + monotonic block indices make the spliced turn a
// single coherent Anthropic message ending in exactly ONE terminal (L3). A watchdog fire never
// continues — its cancellation owns the turn. The continuation decision + cross-round merge live in
// [ReanchorContinuation] (now its own file); this class keeps only the loop.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.spi.ReanchorController
import splice.spi.ToolSearchController

internal class ReanchorRunner(
    private val key: String,
    private val log: LogSink,
    private val postRound: PostRound,
    private val finish: FinishTurn,
    private val signals: RunnerSignals,
    private val toolSearch: ToolSearchController? = null,
) {
    private val rounds = RoundSplice()
    private val continuation = ReanchorContinuation(toolSearch, signals, rounds)

    // [reanchor] is nullable — a turn may reach this runner with search-only continuation (no
    // ReanchorController at all): driveOneTurn routes here whenever EITHER exists, so the seam is
    // total rather than resting on an undocumented cross-object invariant.
    suspend fun run(initialBody: JsonObject, reanchor: ReanchorController?) {
        var body = initialBody
        var attempt = 0
        var searchIndex = 0
        var acc = RoundUsage()
        val salvaged = mutableListOf<TurnOutcome.PartialRound>()
        val absorbedFailures = mutableListOf<TurnOutcome.Failure>()
        while (true) {
            val outcome = postRound(body.toString())
            val next = continuation.continuationForFailure(reanchor, outcome, body, attempt)
            if (next == null) {
                // A search round is inserted HERE — after the failure-continuation is computed and
                // found null, so it never competes with re-anchor for a retryable failure, and
                // only ever fires on a Success (searchContinuation's own type guard).
                val searchNext = continuation.searchContinuation(outcome, body, searchIndex)
                if (searchNext != null) {
                    val searched = outcome as TurnOutcome.Success
                    salvaged.add(rounds.searchPartial(searched, buffered = false))
                    acc = acc.plusRound(searched.usage)
                    body = searchNext
                    searchIndex++
                    signals.onSearchRound(searchIndex)
                    log("[$key] tool search round $searchIndex: answering locally, continuing\n")
                    continue
                }
                // Absorbed failures hit the health split ONLY when the turn ultimately succeeds
                // (a rescued turn must not report a degraded provider as healthy); a turn that
                // ultimately FAILS is attributed exactly once by finishTurn — firing per absorbed
                // round would triple-count one logical failure (HeadServerIntegrationTest).
                if (outcome is TurnOutcome.Success) absorbedFailures.forEach(signals.onRoundFailure::invoke)
                finish(rounds.withFailureSalvage(continuation.finalOutcome(outcome, salvaged, acc), acc))
                return
            }
            val failure = outcome as TurnOutcome.Failure
            absorbedFailures.add(failure)
            failure.partial?.let { p ->
                salvaged.add(p)
                acc = acc.plusRound(p.usage)
            }
            log(
                "[$key] re-anchor ${attempt + 1}: ${failure.type.wireName} mid-stream — " +
                    "continuing from partial output\n",
            )
            body = next
            attempt++
        }
    }
}
