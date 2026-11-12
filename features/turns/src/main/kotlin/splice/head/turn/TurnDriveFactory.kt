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
import splice.head.admission.KeyNaming
import splice.head.hold.ClientWallHeader
import splice.head.hold.HeldTurn
import splice.head.hold.TurnWaitsAtHead
import splice.head.hold.resources.HoldSurfaces
import splice.head.hold.resources.SessionWall
import splice.head.hold.resources.TurnHold
import splice.head.transport.TurnAccountHandoff
import splice.head.wire.ClientChannel
import splice.head.wire.TurnTerminal
import splice.upstream.Provider
import splice.upstream.TurnRetry
import splice.upstream.retry.NON_STREAM_HOLD_MS
import splice.upstream.retry.RetryLedger
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.RemainingTurnWait

internal class TurnDriveFactory(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val health: HeadHealthCounters,
) {
    private val driveSignals = DriveSignals(provider, deps.log, health)
    private val drivePipeline = DrivePipeline(provider, deps)

    /** What a row records as its account when no login is proved for the turn. A key head names the key that
     *  sent the request, and a key head with no key names nothing: "primary" is left for a head holding a login
     *  splice cannot name, so a row never reads as a bare word while a key sat behind it, and never invents one
     *  where no key is there at all. */
    private fun accountLabel(naming: KeyNaming): String? = when (naming) {
        is KeyNaming.Named -> naming.label
        KeyNaming.Missing -> null
        KeyNaming.None -> if (provider.auth is ClientAuthProvider) "claude-code" else "primary"
    }

    /** The session's wall for this request (failure-handling slice 5): the client timeout its session named, a
     *  compaction's own timer where that is sooner, and the few seconds a non-stream request may wait with nothing
     *  written, counted from the request's admission. */
    private fun sessionWall(inputs: TurnInputs): SessionWall {
        val compaction = if (inputs.built.meta.compact) {
            ClientWallHeader.compactionTimerMs(inputs.built.requestBody.toString().length.toLong())
        } else {
            Long.MAX_VALUE
        }
        return SessionWall(deps.seams.clock, inputs.t0 + minOf(inputs.clientWallMs, compaction))
    }

    /** Assemble the per-turn drive around a terminal (SseEmitter for stream, CollectingTerminal for
     *  collect) and its channel — everything else (watchdog, pipeline, headers) is shape-neutral. */
    fun assembleDrive(
        inputs: TurnInputs,
        emitter: TurnTerminal,
        channel: ClientChannel,
        stream: Boolean = true,
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
        val live = deps.traffic.watchdog?.invoke() ?: provider.watchdog
        val budget = if (meta.compact) live.forCompact() else live
        // Paced on the head's watchdog ticker, so the wall and the cap fall on the head's own clock.
        val watchdog = TurnWatchdog(
            budget,
            deps.seams.clock,
            deps.seams.watchdogTicker,
            log = { deps.log("[${provider.key}] $it") },
        )
        val wall = sessionWall(inputs)
        watchdog.wallAt(wall.capAtMs())
        // A collected request may wait silently for a short limit, but generation keeps the session's own wall.
        val holdWall = if (stream) {
            wall
        } else {
            SessionWall(
                deps.seams.clock,
                minOf(wall.capAtMs(), inputs.t0 + NON_STREAM_HOLD_MS),
                marginMs = 0L,
            )
        }
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
            drive.accountLabel.fallback = accountLabel(inputs.accountQuota.keyNaming)
            drive.accountLabel.names = deps.quotaBundle.credentialAccountNames
            drive.accountHandoff = deps.quotaBundle.activePool?.let { TurnAccountHandoff(it, deps.turnQuota) }
            drive.route = provider.route(meta)
            // One ledger for the turn's every post, its waits where the head's client sleeps a retry, told on the turn's
            // own wire.
            attachRetry(drive, holdWall)
        }
    }

    /** A drive's retained wait owns its slot, watchdog, wire and request memory together. */
    private fun attachRetry(drive: TurnDrive, holdWall: SessionWall) {
        val inputs = drive.inputs
        val emitter = drive.emitter
        val watchdog = drive.watchdog
        val channel = drive.channel
        val built = inputs.built
        val waiter = deps.traffic.upstream.retryWaiter
        val hold = TurnHold(
            HeldTurn(deps.traffic.liveTurns.holds, waiter, deps.traffic.upstream.retryWall)
                .also { held -> inputs.slot.held.onRelease { deps.traffic.liveTurns.holds.leave(held) } },
            HoldSurfaces(
                emitter,
                inputs.slot.held,
                watchdog,
                deps.traffic.liveTurns,
                channel.lost,
                inputs.heapLease,
            ),
            holdWall,
            deps.traffic.upstream.retryWall,
        ) { built.requestBody.toString().length.toLong() }
        val ledger = RetryLedger()
        drive.retry = TurnRetry(ledger, TurnWaitsAtHead(waiter, emitter, hold))
        drive.holdServed = Runnable {
            deps.traffic.liveTurns.holds.served(ledger.login)
            ledger.served()
        }
        drive.sourceRoundStarted =
            TurnDrive.SourceRoundStarted { job -> deps.traffic.liveTurns.driving(inputs.slot.held, job) }
    }
}
