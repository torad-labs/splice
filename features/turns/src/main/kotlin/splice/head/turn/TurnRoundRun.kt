// NEW: the per-turn RoundStrategy dispatch (fold/reanchor/post/finish).
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.turn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.core.util.LogSink
import splice.head.round.RoundInterception
import splice.head.round.RoundRunners
import splice.head.round.RoundStrategy
import splice.head.transport.IndependentSourcePost
import splice.head.transport.SseRoundDriver
import splice.head.wire.WireTap
import splice.upstream.Provider
import splice.upstream.RoundBody

internal class TurnRoundRun(
    private val provider: Provider,
    private val log: LogSink,
    private val sseRoundDriver: SseRoundDriver,
    private val turnFinish: TurnFinish,
    /** V4-173: the head's opt-in upstream wire tap, null on every head that did not turn it on. */
    private val wireTap: WireTap?,
    usageStamp: TurnUsageStamp,
) {
    private val sourceRound = IndependentSourcePost(sseRoundDriver, usageStamp)

    suspend fun run(drive: TurnDrive, self: CoroutineScope, turnJob: Job) {
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
        RoundStrategy(
            emitter = drive.emitter,
            runners = RoundRunners(
                key = provider.key,
                log = log,
                signals = drive.signals,
                finish = { outcome -> turnFinish.finishTurn(drive, outcome) },
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

    // The audit still sees exactly the bytes this round POSTs: byteSize counts them without encoding,
    // and the wire tap reads the text only when a tap is actually open.
    private fun recordPost(drive: TurnDrive, body: RoundBody) {
        drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, body.byteSize())
        wireTap?.record(drive.meta, body.text, drive.sentTurnId())
    }
}
