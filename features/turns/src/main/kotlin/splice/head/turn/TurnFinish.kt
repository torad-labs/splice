// PORT-OF: splice/gateway/head/TurnDriver.kt (finishTurn) @ 86f1411 — invariants unchanged:
// terminal frames FIRST (finishStream), stats after — the usage-file rewrite and the cache log
// line must never sit between the last delta and message_stop on the wire. NOT folded into
// TurnTelemetry (HD-24): calling the L3 terminal from a class named "telemetry" would mislabel the
// one call the whole invariant hangs on. The cache-line construction itself DID move to
// TurnTelemetry.cacheLine — that is a log line, not the terminal. Usage stamping lives in
// TurnUsageStamp.kt (concentration, 2026-08-19).
package splice.head.turn

import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.HeadHealthCounters

internal class TurnFinish(
    private val clock: ElapsedClock,
    private val log: LogSink,
    private val usageStamp: TurnUsageStamp,
    private val health: HeadHealthCounters,
    private val telemetry: TurnTelemetry,
) {
    suspend fun finishTurn(drive: TurnDrive, outcome: TurnOutcome) {
        val failure = outcome as? TurnOutcome.Failure
        markPermanence(drive, failure)
        val latencyMs = clock() - drive.t0
        log(
            telemetry.turnLine(
                drive.meta,
                drive.upstreamModel,
                outcome,
                latencyMs,
                drive.watchdog.fired,
                drive.watchdog.held,
            ),
        )
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
        // The conn-reset surface records this same perf snapshot if the terminal write throws.
        markCodeMode(drive, outcome)
        val streamed = Cancellables.runCatchingCancellable {
            drive.pipeline.finishStream(
                drive.emitter,
                outcome,
                drive.meta,
                latencyMs,
            )
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
        if (outcome is TurnOutcome.Failure) health.failure(outcome)
        // DR-87/DR-88: a Success outcome can still end in an ERROR terminal — the collect-path
        // malformed-tool/capacity rewrite (surfaced via TurnTerminal.degradedReason) and the
        // promote-time empty_compact/empty_model. The turn line above rendered the Success; this
        // makes the downgrade visible to the log and to head health (perf carries the honest tag
        // below). Local attribution: the downgrade is the gateway's own call — G20's
        // providerReported stays translator-owned.
        if (outcome is TurnOutcome.Success && !OutcomeTags.isClean(outcomeTag)) {
            val detail = "tag=$outcomeTag; client received an error terminal"
            log(telemetry.errTurn("finish-degraded", drive, detail))
            health.local()
        }
        // V4-117: the failing outcome is in scope here, so the perf row gets its cause and the
        // attempt count the retry loop stamped on it. A Success carries neither, and both default to
        // absent — the row for a healthy turn is byte-identical to what it was before this field.
        failure?.let { drive.trace?.failureSentenceUnlessSpoken(OutcomeSentences.of(it)) }
        telemetry.recordPerf(
            drive,
            outcomeTag,
            cause = failure?.cause?.name,
            layers = failure?.layers ?: 0,
        )
    }

    /** The classified decision survives a terminal write that escapes to the conn-reset recorder. */
    private fun markPermanence(drive: TurnDrive, failure: TurnOutcome.Failure?) {
        failure?.let { drive.perf.setCount(PerfKeys.FAILURE_PERMANENT, if (it.permanent) 1L else 0L) }
    }

    private fun markCodeMode(drive: TurnDrive, outcome: TurnOutcome) {
        val usage = when (outcome) {
            is TurnOutcome.Success -> outcome.usage
            is TurnOutcome.Failure -> outcome.salvagedUsage
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage
        }
        // The same perf snapshot is included in the turn trace, even if the upstream failed.
        if (usage.codeModeDiverged) drive.perf.setCount(PerfKeys.CODE_MODE_DIVERGENCE, 1)
        if (outcome !is TurnOutcome.Success || !usage.localStep) return
        // A turn that began upstream and then emitted a code-mode tool call remains a turn.
        if (drive.perfCounter(PerfKeys.ATTEMPTS) == 0L && drive.perfCounter(PerfKeys.UPSTREAM_REQ_BYTES) == 0L) {
            drive.perf.setCount(PerfKeys.LOCAL_STEP, 1)
        }
    }
}
