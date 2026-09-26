// NEW: the per-turn RoundStrategy dispatch (fold/reanchor/post/finish).
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.turn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.util.LogSink
import splice.head.round.RoundInterception
import splice.head.round.RoundStrategy
import splice.head.transport.SseRoundDriver
import splice.head.wire.WireTap
import splice.upstream.Provider

internal class TurnRoundRun(
    private val provider: Provider,
    private val log: LogSink,
    private val sseRoundDriver: SseRoundDriver,
    private val turnFinish: TurnFinish,
    /** V4-173: the head's opt-in upstream wire tap, null on every head that did not turn it on. */
    private val wireTap: WireTap?,
) {
    suspend fun run(drive: TurnDrive, self: CoroutineScope, turnJob: Job) {
        // Folding is null for sol / every non-codex head → the single-round path is
        // byte-for-byte the pre-fold behaviour (drive straight to the real emitter,
        // finish once). A fold-eligible turn hands the loop to FoldRunner. Which runner
        // drives this turn is [RoundStrategy]'s decision (HD-24).
        val fold = provider.foldController(drive.meta)
        val reanchor = provider.reanchorController(drive.meta)
        RoundStrategy(
            key = provider.key,
            log = log,
            emitter = drive.emitter,
            signals = drive.signals,
            // V4-173: THE choke point. Every upstream request of every runner — the single round,
            // each fold round, each re-anchor, each tool-search continuation — and whatever an
            // interceptor substituted, passes through one of these two lambdas as the string the
            // driver POSTs. Recorded here, an audit sees exactly the bytes, not the turn's first draft.
            postRoundToSink = { bodyJson, sink ->
                wireTap?.record(drive.meta, bodyJson)
                sseRoundDriver.postRound(drive, bodyJson, sink, self, turnJob)
            },
            postRound = { bodyJson ->
                wireTap?.record(drive.meta, bodyJson)
                sseRoundDriver.postRound(drive, bodyJson, drive.emitter, self, turnJob)
            },
            finish = { outcome -> turnFinish.finishTurn(drive, outcome) },
            toolSearch = drive.toolSearch,
            interception = RoundInterception(
                interceptor = drive.roundInterceptor,
                rawRoundObserved = drive::recordRawRound,
            ),
        ).run(drive.requestBody, fold, reanchor)
    }
}
