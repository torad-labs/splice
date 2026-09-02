// NEW: the per-turn RoundStrategy dispatch (fold/reanchor/post/finish).
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.util.LogSink
import splice.gateway.round.RoundStrategy
import splice.spi.Provider

internal class TurnRoundRun(
    private val provider: Provider,
    private val log: LogSink,
    private val sseRoundDriver: SseRoundDriver,
    private val turnFinish: TurnFinish,
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
            postRoundToSink = { bodyJson, sink ->
                sseRoundDriver.postRound(drive, bodyJson, sink, self, turnJob)
            },
            postRound = { bodyJson ->
                sseRoundDriver.postRound(drive, bodyJson, drive.emitter, self, turnJob)
            },
            finish = { outcome -> turnFinish.finishTurn(drive, outcome) },
            toolSearch = drive.toolSearch,
        ).run(drive.requestBody, fold, reanchor)
    }
}
