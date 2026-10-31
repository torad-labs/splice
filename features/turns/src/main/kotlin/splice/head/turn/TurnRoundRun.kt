// NEW: the per-turn RoundStrategy dispatch (fold/reanchor/post/finish).
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.turn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.util.LogSink
import splice.head.round.CompletedRounds
import splice.head.round.Recovering
import splice.head.round.RoundInterception
import splice.head.round.RoundRunners
import splice.head.round.RoundSteps
import splice.head.round.RoundStrategy
import splice.head.transport.IndependentSourcePost
import splice.head.transport.SseRoundDriver
import splice.head.wire.WireTap
import splice.upstream.Provider
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.TurnRetry
import splice.upstream.TurnWaits
import splice.upstream.retry.RetryLedger
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamEnding

internal class TurnRoundRun(
    private val provider: Provider,
    private val log: LogSink,
    private val sseRoundDriver: SseRoundDriver,
    private val turnFinish: TurnFinish,
    /** V4-173: the head's opt-in upstream wire tap, null on every head that did not turn it on. */
    private val wireTap: WireTap?,
    usageStamp: TurnUsageStamp,
    /** The head's client: the waiter and the wall clock its posts retry on, which the turn's rounds take their steps on
     *  too where a drive carries no ledger of its own. */
    private val upstream: UpstreamClient,
) {
    private val sourceRound = IndependentSourcePost(sseRoundDriver, usageStamp)

    /** Null when the turn finished; the ending of a round that never had an outcome, written after the turn unwinds. */
    suspend fun run(drive: TurnDrive, self: CoroutineScope, turnJob: Job): UpstreamEnding? {
        // No upstream POST opens an adopted source step. Open it here so signed live progress can flow.
        if (drive.roundInterceptor?.resumesSource() == true) {
            drive.channel.statusGate?.open()
            drive.emitter.ensureStarted()
        }
        // Folding is null for sol / every non-codex head → the single-round path is
        // byte-for-byte the pre-fold behaviour (drive straight to the real emitter,
        // finish once). A fold-eligible turn hands the loop to FoldRunner. Which runner
        // drives this turn is [RoundStrategy]'s decision (HD-24).
        val fold = provider.foldPolicy(drive.meta)
        val reanchor = provider.reanchorPolicy(drive.meta)
        return RoundStrategy(
            emitter = drive.emitter,
            runners = RoundRunners(
                key = provider.key,
                log = RetryNotice { log(it.trimEnd('\n') + "\n") },
                // Failure-handling slice 2: a runner's recovery reaches the person only as the line this turn's wire
                // shows once per recovery episode, said by the turn's voice from the cause.
                signals = drive.signals.copy(recovering = Recovering { drive.emitter.recovering(it) }),
                finish = { outcome -> turnFinish.finishTurn(drive, outcome) },
                steps = RoundSteps(
                    turnRetry(drive),
                    upstream.retryWall,
                    // A round is cut short only where its provider's replies say so (codex's 518n-2, CodexReplies).
                    CompletedRounds { provider.replies.completed(drive.meta.route.upstreamModel, it.usage) },
                ),
                toolSearch = drive.toolSearch,
            ),
            // V4-173: THE choke point. Every upstream request of every runner — the single round,
            // each fold round, each re-anchor, each tool-search continuation — and whatever an
            // interceptor substituted, passes through one of these two lambdas as the string the
            // driver POSTs. Recorded here, an audit sees exactly the bytes, not the turn's first draft.
            postRoundToSink = { body, sink ->
                recordPost(drive, body)
                sourceRound.post(drive, body, sink, self, turnJob)
            },
            postRound = { body ->
                recordPost(drive, body)
                sseRoundDriver.postRound(drive, body, drive.emitter, self, turnJob)
            },
            interception = RoundInterception(
                interceptor = drive.roundInterceptor,
                rawRoundObserved = drive::recordRawRound,
                postingRow = drive.sourceRow,
            ),
        ).run(drive.requestBody, fold, reanchor, drive.perf)
    }

    /** The turn's one ledger and waits, which its posts spend from too; a drive built without one keeps a ledger for
     *  its rounds and waits on the client's waiter, as a post with no turn around it does. */
    private fun turnRetry(drive: TurnDrive): TurnRetry =
        drive.retry ?: TurnRetry(RetryLedger(), TurnWaits { afterMs, _, _ -> upstream.retryWaiter.wait(afterMs) })

    // The audit still sees exactly the bytes this round POSTs, and the wire tap reads the text only when a tap is
    // actually open. The round's size on its perf row is stamped by the transport that sends the body, from what that
    // transport produced, so nothing here walks the body to count it.
    private fun recordPost(drive: TurnDrive, body: RoundBody) {
        wireTap?.record(drive.meta, body.text, drive.sentTurnId())
    }
}
