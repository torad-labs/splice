// PORT-OF: splice/gateway/head/TurnDriver.kt (ReanchorRunner.run, the class shell) @ 86f1411 —
// invariants unchanged: mid-stream re-anchoring loop (eli design 2026-07-24), the LIVE-emitter
// counterpart of [FoldRunner], split from splice.gateway.head (HD-24, the round subsystem's own
// package). Rounds drive the real wire directly — committed blocks stay; a round that fails with a
// continuable partial re-POSTs the continuation and APPENDS; everything else finishes with the
// round's honest outcome. The emitter's seal + monotonic block indices make the spliced turn a
// single coherent Anthropic message ending in exactly ONE terminal (L3). This header used to end
// "a watchdog fire never continues — its cancellation owns the turn", which DR-7 made false: an
// Idle fire now reaps one ROUND, not the turn, and a stalled round's partial is salvageable and
// continuable. The continuation decision + cross-round merge live in [ReanchorContinuation] (now
// its own file), which states the current rule; this class keeps only the loop.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.upstream.ReanchorController
import splice.upstream.RetryBackoff
import splice.upstream.RetryNotice
import splice.upstream.ToolSearchController
import splice.upstream.codemode.ProcessWaiter
import splice.upstream.transport.UpstreamTransport

internal class ReanchorRunner(
    private val key: String,
    private val log: RetryNotice,
    private val postRound: PostRound,
    private val finish: FinishTurn,
    private val signals: RunnerSignals,
    private val toolSearch: ToolSearchController? = null,
    private val backoff: RetryBackoff = UpstreamTransport().defaultBackoff(ProcessWaiter()),
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
            val cont = continuation.continuationForFailure(reanchor, outcome, body, attempt)
            if (cont == null) {
                // A search round is inserted HERE — after the failure-continuation is computed and
                // found null, so it never competes with re-anchor for a retryable failure, and
                // only ever fires on a Success (searchContinuation's own type guard).
                val searchNext = continuation.searchContinuation(outcome, body, searchIndex)
                val searched = outcome as? TurnOutcome.Success
                if (searchNext != null && searched != null) {
                    salvaged.add(rounds.searchPartial(searched, buffered = false))
                    acc = acc.plusRound(searched.usage)
                    body = searchNext
                    searchIndex++
                    signals.onSearchRound(searchIndex)
                    log("[$key] tool search round $searchIndex: answering locally, continuing\n")
                    continue
                }
                // Absorbed failures hit the health split unless the turn itself ultimately FAILS
                // — that one is attributed exactly once by finishTurn, and firing per absorbed
                // round would triple-count one logical failure (HeadServerIntegrationTest). A
                // rescued turn must not report a degraded provider as healthy, and (DR-125) a
                // ClientAbandoned ending is attributed nowhere else at all: pre-fix a degraded
                // provider grinding retries while clients hung up kept head health clean.
                if (finishOnToolCut(outcome, salvaged, acc, absorbedFailures)) return
                attributedAbsorbed(outcome, absorbedFailures, signals)
                // V4-116 (4): [attempt] is how many times this turn was already re-anchored, so the
                // ending can say what splice did instead of reporting one round's stall as the
                // turn's verdict.
                finish(rounds.withFailureSalvage(continuation.finalOutcome(outcome, salvaged, acc, attempt), acc))
                return
            }
            val failure = cont.failure
            absorbedFailures.add(failure)
            failure.partial?.let { p ->
                salvaged.add(p)
                acc = acc.plusRound(p.usage)
            }
            // A clean-slate restart returns the request verbatim; log it as what it is rather
            // than claiming a partial that does not exist.
            val restarted =
                if (cont.body == body) "restarting the round from scratch" else "continuing from partial output"
            log("[$key] re-anchor ${attempt + 1}: ${failure.type.wireName} mid-stream — $restarted\n")
            // V4-116 (5): the continuation is SPENT here — right where the loop commits to another
            // POST and the counter that names it moves. Stamped at the same point as [attempt] on
            // purpose: a number that could disagree with the loop's own count is worse than no
            // number, and the ending message (gaveUp) quotes this one.
            signals.onReanchor()
            backoff(attempt, 0)
            body = cont.body
            attempt++
        }
    }

    /** V4-76: BEFORE the failure is finalized, ask whether the cut came AFTER a COMPLETED tool call.
     *  If it did, the client holds a well-formed tool call it can simply run, so the turn ends CLEAN
     *  at stop_reason tool_use instead of an error frame the client can only finalize as "Connection
     *  lost mid-response" (it never retries after content). An OPEN tool tear is refused and falls
     *  through to today's honest error. See ReanchorContinuation.toolCutSalvage.
     *
     *  V4-106 lifts this out of [run] unchanged: that loop sits at detekt's LongMethod ceiling and
     *  the wall's narrowing had to buy its lines somewhere. A RESCUED turn must still report the
     *  degraded provider — [attributedAbsorbed] only fires when the turn does not FAIL, and this
     *  turn no longer does, so skipping it would make a provider that tore mid-tool look healthy.
     *  Returns true when the turn was finished here, so [run] returns. */
    private suspend fun finishOnToolCut(
        outcome: TurnOutcome,
        salvaged: List<TurnOutcome.PartialRound>,
        acc: RoundUsage,
        absorbedFailures: List<TurnOutcome.Failure>,
    ): Boolean {
        val cutSalvage = continuation.toolCutSalvage(outcome, salvaged, acc) ?: return false
        absorbedFailures.forEach(signals.onRoundFailure::invoke)
        log(
            "[$key] tool-cut salvage: stream cut after a COMPLETED tool_use — ending " +
                "clean at tool_use so the client runs the call and continues\n",
        )
        finish(cutSalvage)
        return true
    }

    /** V4-106: the absorbed-round health split, lifted out of [run] for the same reason — that loop
     *  is at detekt's CyclomaticComplexMethod ceiling too. Behaviour is identical: an absorbed round
     *  is attributed to health unless the turn's OWN outcome is a Failure, which finishTurn
     *  attributes exactly once (firing per absorbed round would triple-count one failure). */
    private suspend fun attributedAbsorbed(
        outcome: TurnOutcome,
        absorbedFailures: List<TurnOutcome.Failure>,
        signals: RunnerSignals,
    ) {
        if (outcome !is TurnOutcome.Failure) absorbedFailures.forEach(signals.onRoundFailure::invoke)
    }
}
