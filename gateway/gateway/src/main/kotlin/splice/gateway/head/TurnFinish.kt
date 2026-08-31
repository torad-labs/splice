// PORT-OF: splice/gateway/head/TurnDriver.kt (finishTurn) @ 86f1411 — invariants unchanged:
// terminal frames FIRST (finishStream), stats after — the usage-file rewrite and the cache log
// line must never sit between the last delta and message_stop on the wire. NOT folded into
// TurnTelemetry (HD-24): calling the L3 terminal from a class named "telemetry" would mislabel the
// one call the whole invariant hangs on. The cache-line construction itself DID move to
// TurnTelemetry.cacheLine — that is a log line, not the terminal. Usage stamping lives in
// TurnUsageStamp.kt (concentration, 2026-08-19).
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink

internal class TurnFinish(
    private val clock: ElapsedClock,
    private val log: LogSink,
    private val usageStamp: TurnUsageStamp,
    private val health: HeadHealthCounters,
    private val telemetry: TurnTelemetry,
) {
    suspend fun finishTurn(drive: TurnDrive, outcome: TurnOutcome) {
        val latencyMs = clock() - drive.t0
        log(telemetry.turnLine(drive.meta, drive.upstreamModel, outcome, latencyMs))
        // DR-129: terminal frames still go FIRST (the header invariant — usage I/O must never sit
        // between the last delta and message_stop), but the stamps below must survive the
        // dead-client IOException emitTerminal rethrows after sealing: the outcome's usage is IN
        // HAND and already billed, and pre-fix the turn landed as a TOKEN-LESS conn-reset row
        // (stampSuccess is the only production writer of appendOutputTokens for successes).
        // Catch-stamp-rethrow, never reorder. The conn-reset surface that catches the rethrow
        // keeps owning the row tag and health (recordPerf below is skipped on the throw path
        // exactly as before — the counts stamped here ride that row via the shared TurnPerf).
        // Cancellation still skips the stamps (runCatchingCancellable rethrows immediately):
        // a cancellation seal is the documented no-bill case, unchanged.
        val streamed = Cancellables.runCatchingCancellable {
            drive.pipeline.finishStream(drive.emitter, outcome, drive.meta, latencyMs)
        }
        drive.perf.mark(PerfKeys.FINISH)
        (outcome as? TurnOutcome.Success)?.let { usageStamp.stampSuccess(drive, it) }
        (outcome as? TurnOutcome.Failure)?.let { usageStamp.stampSalvaged(drive, it.salvagedUsage) }
        // DR-125: an abandoned turn's absorbed rounds burned the same real billed tokens.
        (outcome as? TurnOutcome.ClientAbandoned)?.let { usageStamp.stampSalvaged(drive, it.salvagedUsage) }
        val outcomeTag = streamed.getOrThrow()
        // G20 (corrected, review 2026-07-19): attribution rides the outcome's provenance flag, not
        // the ErrorType — the old OVERLOADED-implies-local heuristic misfiled a passthrough
        // provider's genuine overloaded_error as local-origin. providerReported is set ONLY where a
        // translator parsed an error the upstream actually sent.
        if (outcome is TurnOutcome.Failure) {
            if (outcome.providerReported) health.provider() else health.local()
        }
        // DR-87/DR-88: a Success outcome can still end in an ERROR terminal — the collect-path
        // malformed-tool/capacity rewrite (surfaced via TurnTerminal.degradedReason) and the
        // promote-time empty_compact/empty_model. The turn line above rendered the Success; this
        // makes the downgrade visible to the log and to head health (perf carries the honest tag
        // below). Local attribution: the downgrade is the gateway's own call — G20's
        // providerReported stays translator-owned.
        if (outcome is TurnOutcome.Success && outcomeTag != "ok") {
            log(telemetry.errTurn("finish-degraded", drive, "tag=$outcomeTag — client received an error terminal"))
            health.local()
        }
        telemetry.recordPerf(drive, outcomeTag)
    }
}
