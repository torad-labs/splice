// NEW: split from HeadServer (2026-07-18, the audit's god-file finding — done properly instead
// of suppressed): everything PER-TURN lives here. HeadServer owns the server shell + admission;
// this file owns the drive: SSE channel wiring → upstream POST → watchdog + client-liveness
// pinger → translator → honesty pipeline → sole terminal → telemetry.
//
// HD-24 (concentration campaign): this class held twelve types and imported nine subsystems in one
// file. Twenty members moved out into three packages — splice.gateway.round (the round runners,
// which already had zero dependence on TurnDrive/TurnDriver), sibling files in
// splice.gateway.head (failure classification, honest-ending, per-turn context assembly), and
// splice.gateway.wire (the client write surface, which that package already owns the rest of).
// TurnDriver keeps exactly what HeadServer needs: open the right response shape, drive one turn to
// an honest end, hold health counters. See TurnDrive.kt, TurnDriveFactory.kt, TurnFailures.kt,
// TurnEnding.kt, CancellationSeal.kt, TurnFinish.kt, TurnTelemetry.kt, SseRoundDriver.kt,
// TearAwareEvents.kt, DrivePorts.kt in this package; RoundPorts.kt, RoundValues.kt, RoundSplice.kt,
// RoundStrategy.kt, FoldRunner.kt, FoldRounds.kt, ReanchorRunner.kt, ReanchorContinuation.kt in
// splice.gateway.round; ClientChannel.kt, TurnWiring.kt in splice.gateway.wire.
package splice.gateway.head

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.spi.BuiltTurn
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.RetryNotice

/** Drives one streamed turn end-to-end. Owned by HeadServer; one instance per head. */
internal class TurnDriver(
    private val provider: Provider,
    private val deps: HeadDeps,
) {
    private val log get() = deps.log

    private val telemetry = TurnTelemetry(provider.key, deps.perfStats, deps.log, deps.clock)
    private val health = HeadHealthCounters()
    private val failures = TurnFailures(provider)
    private val zeroEvent = ZeroEventFailure(provider, log)
    private val driveFactory = TurnDriveFactory(provider, deps, health)
    private val sseRoundDriver = SseRoundDriver(
        WsRoundDriver(
            provider,
            log,
            ZeroEventClassifier { drive, outcome, bodyText, eventsBase ->
                zeroEvent.classify(
                    drive,
                    outcome,
                    bodyText,
                    drive.perfCounter(PerfKeys.EVENTS_IN) - eventsBase,
                    telemetry,
                )
            },
        ),
        SseRoundPost(
            provider,
            deps.upstream,
            deps.usageStore,
            deps.quota,
            SseRoundConsume(provider, zeroEvent, telemetry, TearAwareEvents(provider, deps.log)),
            RetryNotice { log("[${provider.key}] $it\n") },
        ),
    )
    private val ending = TurnEnding(
        log,
        telemetry,
        health,
        TurnConnEnd(provider, log, telemetry, failures, health),
        TurnKnownEnd(provider, log, telemetry, failures, health),
    )
    private val cancellationSeal = CancellationSeal(provider, log, telemetry, health)
    private val turnFinish = TurnFinish(
        deps.clock,
        log,
        TurnUsageStamp(deps.usageStore, log, telemetry),
        health,
        telemetry,
    )
    private val oneDrive = TurnOneDrive(
        provider,
        deps,
        TurnRoundRun(provider, log, sseRoundDriver, turnFinish),
    )
    private val streamer = TurnStreamer(provider, deps, driveFactory, this)

    // Pre-priced HD-24 contingency: collect() moved to its own file (CollectTurn.kt) because the
    // un-split TurnDriver.kt measured ratio 1.83, just over the 1.8 gate.
    private val collectTurn = CollectTurn(provider, driveFactory, this, deps.quota, deps.clientWindows)

    /** G20: passive health snapshot for HeadServer.healthSnapshot() — the control-plane's
     *  /api/heads aggregation, never the per-head /health liveness route (external contract). */
    internal fun healthCounters(): HeadHealthCounts = health.snapshot()

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        streamer.stream(call, TurnInputs(built, slot, t0, perf))
    }

    /** Drive one turn, emit classified failures, and — if a cancellation lands (head stop,
     *  write-timeout, parent cancel) — seal the terminal honestly before rethrowing (see
     *  [CancellationSeal]). ONE copy, shared by [stream] and [collect], so the sealing contract
     *  cannot drift between them.
     *
     *  [seal] gates the cancellation seal to the STREAM path only: [collect] passes seal=false —
     *  it never commits a 200 before its terminal respondText, so a cancelled collect has no
     *  half-open response to rescue; sealing there only wrote an error body nobody reads while
     *  polluting localOriginErrors (review 2026-07-22 round 3).
     *
     *  catchingTurnFailure rethrows CancellationException (caught HERE, after sealing);
     *  runCatchingCancellable (splice.core.util) doesn't fit — its catch list is I/O +
     *  (de)serialization for local best-effort work, not the turn-transport failure classes
     *  [TurnEnding.emitFailure] dispatches on.
     *
     *  `internal`, not `private` (named widening, HD-24): [CollectTurn] calls this too, so the L3
     *  seal contract stays the one copy stream and collect both share, across the file split. */
    internal suspend fun driveSealingCancellation(
        drive: TurnDrive,
        pingClient: Boolean = true,
        seal: Boolean = true,
    ) {
        try {
            failures.catchingTurnFailure { oneDrive.driveOneTurn(drive, pingClient) }
                .onFailure { e -> ending.emitFailure(drive, e) }
        } catch (e: CancellationException) {
            cancellationSeal.seal(drive, seal)
            throw e
        }
    }

    /** Non-stream sibling of [stream]: Claude Code sends stream:false on some internal calls (the
     *  Node predecessor served them by collecting the terminal object). See [CollectTurn]. */
    suspend fun collect(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) =
        collectTurn.collect(call, built, slot, t0, perf)

    /** Head restart = fresh diagnostic baseline (the HeadHealth doc's promised behavior; the
     *  counters lived through control-plane restarts before — review 2026-07-19). */
    internal fun resetHealth() = health.reset()
}
