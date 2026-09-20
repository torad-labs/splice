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
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.spi.Provider
import splice.spi.RetryNotice

/** Drives one streamed turn end-to-end. Owned by HeadServer; one instance per head. */
internal class TurnDriver(
    private val provider: Provider,
    private val deps: HeadDeps,
    /** NO DEFAULT (V4-105): the driver does not own this, it SHARES it — the same instance is what
     *  makes a replay visible to the server that will serve it, and a defaulted `CompactionReplay()`
     *  let a caller get a private empty one that compiled, ran, and silently could not replay
     *  anything. A value that must be shared is exactly the parameter a default must not supply, so
     *  the compiler now asks every construction site for it. */
    private val compactionReplay: CompactionReplay,
) {
    private val log get() = deps.log

    private val telemetry =
        TurnTelemetry(
            provider.key,
            deps.stores.perfStats,
            deps.log,
            deps.seams.clock,
            deps.stores.economicsStore,
            deps.seams.events,
        )
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
            deps.stores.usageStore,
            deps.turnQuota,
            SseRoundConsume(provider, zeroEvent, telemetry, TearAwareEvents(provider, deps.log)),
            RetryNotice { log("[${provider.key}] $it\n") },
        ),
        provider.upstreamUrl,
    )
    private val ending = TurnEnding(
        log,
        telemetry,
        health,
        TurnConnEnd(provider, log, telemetry, failures, health),
        TurnKnownEnd(provider, log, telemetry, failures, health),
    )
    private val usageStamp = TurnUsageStamp(deps.stores.usageStore, log, telemetry)
    private val cancellationSeal = CancellationSeal(provider, log, telemetry, health, usageStamp)
    private val turnFinish = TurnFinish(
        deps.seams.clock,
        log,
        usageStamp,
        health,
        telemetry,
    )
    private val oneDrive = TurnOneDrive(
        provider,
        deps,
        TurnRoundRun(provider, log, sseRoundDriver, turnFinish, deps.stores.wireTap),
    )

    /** V4-99 item 5: the seal contract the two drive entries actually need, held here so they
     *  no longer take the whole driver to reach one function. */
    // `internal`, not `private`: a test that builds a TurnStreamer to exercise the detached
    // stop order needs the contract the entry now takes, and it cannot reach the four
    // collaborators this is assembled from (V4-99 item 5).
    internal val sealedDrive = SealedDrive(failures, oneDrive, ending, cancellationSeal)
    private val streamer = TurnStreamer(provider, deps, driveFactory, sealedDrive, compactionReplay)
    private val localResponses = LocalResponses(provider, deps, compactionReplay)

    // Pre-priced HD-24 contingency: collect() moved to its own file (CollectTurn.kt) because the
    // un-split TurnDriver.kt measured ratio 1.83, just over the 1.8 gate.
    private val collectTurn = CollectTurn(
        provider,
        driveFactory,
        sealedDrive,
        deps.turnQuota,
        deps.stores.clientWindows,
    )

    /** G20: passive health snapshot for HeadServer.healthSnapshot() — the control-plane's
     *  /api/heads aggregation, never the per-head /health liveness route (external contract). */
    internal fun healthCounters(): HeadHealthCounts = health.snapshot()

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, inputs: TurnInputs): Boolean =
        streamer.stream(call, inputs)

    /** Claude Code's activity side query, answered by the proxy (ActivityLabel): no upstream turn. */
    suspend fun answerLocally(call: ApplicationCall, local: Preparation.Local) = localResponses.answer(call, local)

    /** A compaction retry served from the detached first attempt's recording (CompactionReplay). */
    suspend fun replay(call: ApplicationCall, replayed: Preparation.Replay) = localResponses.replay(call, replayed)

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
    /** Non-stream sibling of [stream]: Claude Code sends stream:false on some internal calls (the
     *  Node predecessor served them by collecting the terminal object). See [CollectTurn]. */
    suspend fun collect(call: ApplicationCall, inputs: TurnInputs): Boolean =
        collectTurn.collect(call, inputs)

    /** V4-99 item 4: ONE entry for every locally-refused turn (no account selectable, or the
     *  admission rate limit). Local admission, not an upstream failure, so it must not colour
     *  upstream health — and it must be VISIBLE: see TurnTelemetry.recordLocalRefusal. */
    fun recordLocalRefusal(meta: TurnMeta, perf: TurnPerf, t0: Long, refusal: LocalRefusal) {
        health.local()
        telemetry.recordLocalRefusal(meta, perf, t0, refusal)
    }

    /** Head restart = fresh diagnostic baseline (the HeadHealth doc's promised behavior; the
     *  counters lived through control-plane restarts before — review 2026-07-19). */
    internal fun resetHealth() {
        health.reset()
        deps.quotaBundle.accountPool?.reset()
    }

    /** Head stop: end the detached compactions this head still drives; the scope stays usable for
     *  the restart (TurnStreamer.stopDetached). */
    internal fun stopDetached() = streamer.stopDetached()
}
