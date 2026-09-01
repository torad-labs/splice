// PORT-OF: splice/gateway/head/TurnDriver.kt (FoldRunner.run, the class shell) @ 86f1411 —
// invariants unchanged: the reasoning-continuation fold state machine, split from
// splice.gateway.head (HD-24, the round subsystem's own package). Drives rounds via [postRound],
// BUFFERING each round's tentative final output while reasoning streams live; a truncated round's
// output is DISCARDED and the next round re-POSTed with its reasoning replayed; the terminal
// round's output is FLUSHED and [finish] called exactly ONCE with usage summed across every round —
// one honest terminal downstream (L3). The fold-continuation/re-anchor/search checks live in
// [FoldRounds] (detekt's own extraction, now its own file); this class keeps only the loop.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.gateway.wire.BufferingWireSink
import splice.spi.FoldController
import splice.spi.ProcessWaiter
import splice.spi.ReanchorController
import splice.spi.RetryBackoff
import splice.spi.RetryNotice
import splice.spi.ToolSearchController
import splice.spi.UpstreamTransport
import splice.spi.WireSink

internal class FoldRunner(
    // Only the buffer's `real` sink — never a terminal here (L3: FoldRunner finishes via [finish]).
    private val emitter: WireSink,
    private val key: String,
    private val log: RetryNotice,
    private val postRound: PostRoundToSink,
    private val finish: FinishTurn,
    private val reanchor: ReanchorController? = null,
    private val signals: RunnerSignals = RunnerSignals(),
    private val toolSearch: ToolSearchController? = null,
    private val backoff: RetryBackoff = UpstreamTransport().defaultBackoff(ProcessWaiter()),
) {
    private val rounds = RoundSplice()
    private val foldRounds = FoldRounds(key, log, reanchor, signals, toolSearch, finish, rounds)

    suspend fun run(initialBody: JsonObject, fold: FoldController) {
        var body = initialBody
        var acc = RoundUsage()
        var roundIndex = 0
        var reanchorAttempt = 0
        var searchIndex = 0
        val salvaged = mutableListOf<TurnOutcome.PartialRound>()
        val absorbedFailures = mutableListOf<TurnOutcome.Failure>()
        while (true) {
            val buffer = BufferingWireSink(emitter)
            val outcome = postRound(body.toString(), buffer)
            val success = outcome as? TurnOutcome.Success
            if (success != null) acc = acc.plusRound(success.usage)

            // Fold-continuation and search are two of this loop's three continuation triggers,
            // tried in that fixed precedence (a truncated round re-runs and re-emits its own
            // search call next round, so this ordering is unchanged from before the extraction —
            // detekt 2026-07-24: inlined here the loop carried 2 `continue`s + CC 11 + 50 lines).
            val cursor = RoundCursor(body, roundIndex, searchIndex)
            val nextRound = foldRounds.nextRoundBody(fold, outcome, buffer, salvaged, cursor)
            if (nextRound != null) {
                body = nextRound.body
                roundIndex = nextRound.roundIndex
                searchIndex = nextRound.searchIndex
                continue
            }

            // Trigger B (code-review 2026-07-24: fold-eligible models — the truncation-prone
            // ones — previously had NO re-anchor cover). The round's final output was BUFFERED,
            // never forwarded, so bodyText is stripped from the salvage: replaying
            // never-forwarded prose as "already written" would desync the client's wire; the
            // retried round re-answers cleanly from its reasoning envelopes. Live thinking
            // already on the wire stays (append-only).
            val retry = foldRounds.continuationForFailedRound(outcome, body, reanchorAttempt)
            if (retry == null) {
                // health for absorbed rounds unless the final outcome is itself a Failure
                // (attributed once by finishTurn) — see ReanchorRunner; DR-125 added abandoned.
                if (outcome !is TurnOutcome.Failure) absorbedFailures.forEach(signals.onRoundFailure::invoke)
                foldRounds.finalize(rounds.withFailureSalvage(outcome, acc), buffer, salvaged, acc.toUsage())
                return
            }
            val failure = outcome as TurnOutcome.Failure
            absorbedFailures.add(failure)
            failure.partial?.let { p ->
                // Strip BOTH buffered-text signals: the prose never reached the client, so the
                // salvage must not let the discarded round vouch for text in the merge either
                // (emittedText=true over empty content would defeat the empty-model honesty
                // gate — review-pr 2026-07-24). thinkingText STAYS: fold-mode reasoning streams
                // LIVE to the wire, so it legitimately belongs in the mirror merge.
                // emittedThinking rides along with thinkingText for the same reason.
                salvaged.add(p.copy(bodyText = "", emittedText = false))
                acc = acc.plusRound(p.usage)
            }
            buffer.discard()
            log("[$key] fold re-anchor ${reanchorAttempt + 1}: ${failure.type.wireName} mid-round — retrying\n")
            backoff(reanchorAttempt, 0)
            body = retry
            reanchorAttempt++
        }
    }
}
