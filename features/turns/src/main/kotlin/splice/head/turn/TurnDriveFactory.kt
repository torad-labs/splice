// PORT-OF: splice/gateway/head/TurnDriver.kt (assembleDrive) @ 86f1411 — invariants unchanged: the
// construction site of the per-turn drive — perf stamping, TurnWatchdog, RunnerSignals,
// TurnPipeline, output clamp. Split out (HD-24) so it can take [deps] whole (dropping
// splice.core.util and splice.head.usage from TurnDriver, whose last named references left with
// it) instead of the loose ElapsedClock/LogSink getters TurnDriver used to forward. [health] is a
// separate constructor param, not read off HeadDeps: it is the SAME HeadHealthCounters instance
// TurnDriver owns across every turn (cumulative counting), not a per-turn dependency.
package splice.head.turn

import splice.core.auth.ClientAuthProvider
import splice.core.perf.PerfKeys
import splice.head.HeadDeps
import splice.head.HeadHealthCounters
import splice.head.transport.TurnAccountHandoff
import splice.head.wire.ClientChannel
import splice.head.wire.TurnTerminal
import splice.upstream.Provider
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.RemainingTurnWait

internal class TurnDriveFactory(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val health: HeadHealthCounters,
) {
    private val driveSignals = DriveSignals(provider, deps.log, health)
    private val drivePipeline = DrivePipeline(provider, deps)

    /** Assemble the per-turn drive around a terminal (SseEmitter for stream, CollectingTerminal for
     *  collect) and its channel — everything else (watchdog, pipeline, headers) is shape-neutral. */
    fun assembleDrive(
        inputs: TurnInputs,
        emitter: TurnTerminal,
        channel: ClientChannel,
    ): TurnDrive {
        val built = inputs.built
        val perf = inputs.perf
        val meta = built.meta
        // The actual serialized round supplies wire size at TurnRoundRun's post boundary.
        // Tool-surface partition sizes — the expected-delta instrument (#959): setCount (not add)
        // so a request that stamped tools_deferred=0 is VISIBLE, never silently absent.
        meta.tools.eager?.let { perf.setCount(PerfKeys.TOOLS_EAGER, it.toLong()) }
        meta.tools.deferred?.let { perf.setCount(PerfKeys.TOOLS_DEFERRED, it.toLong()) }
        // A compact turn's silence before its first output is bounded by totalCap only — see
        // WatchdogBudget.forCompact for the live evidence. Normal turns keep the provider budget.
        val budget = if (meta.compact) provider.watchdog.forCompact() else provider.watchdog
        val watchdog = TurnWatchdog(budget, deps.seams.clock, log = { deps.log("[${provider.key}] $it") })
        // Retry admission shares the watchdog's renewal, rather than a second elapsed-time cap.
        // The origin remains after admission and preparation, never the time spent queued.
        val remainingTurnWait = RemainingTurnWait(watchdog::remainingMs)
        val signals = driveSignals.make(watchdog, channel, perf)
        return TurnDrive(
            inputs = inputs,
            emitter = emitter,
            watchdog = watchdog,
            pipeline = drivePipeline.make(meta),
            signals = signals,
            channel = channel,
            remainingTurnWait = remainingTurnWait,
        ).also { drive ->
            // A key head names the key that sent the request; "primary" is left for a head that holds a login
            // splice cannot name, so a row never reads as a bare word while a key sat behind it.
            drive.fallbackAccountLabel = inputs.accountQuota.keyLabel
                ?: if (provider.auth is ClientAuthProvider) "claude-code" else "primary"
            drive.credentialAccountNames = deps.quotaBundle.credentialAccountNames
            drive.accountHandoff = deps.quotaBundle.activePool?.let { TurnAccountHandoff(it, deps.turnQuota) }
            drive.sourceRoundStarted =
                TurnDrive.SourceRoundStarted { job -> deps.traffic.liveTurns.driving(inputs.slot, job) }
        }
    }
}
