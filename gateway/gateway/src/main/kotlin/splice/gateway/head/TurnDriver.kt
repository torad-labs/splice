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

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.core.perf.TurnPerf
import splice.gateway.round.RoundStrategy
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.SseEmitterFactory
import splice.gateway.wire.TurnWiring
import splice.spi.BuiltTurn
import splice.spi.InflightGate
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean

/** Drives one streamed turn end-to-end. Owned by HeadServer; one instance per head. */
internal class TurnDriver(
    private val provider: Provider,
    private val deps: HeadDeps,
) {
    private val log get() = deps.log
    private val clock get() = deps.clock

    private val telemetry = TurnTelemetry(provider.key, deps.perfStats, deps.log, deps.clock)
    private val health = HeadHealthCounters()
    private val wiring = TurnWiring()
    private val failures = TurnFailures(provider, log)
    private val emitters = SseEmitterFactory()
    private val driveFactory = TurnDriveFactory(provider, deps, health)
    private val sseRoundDriver = SseRoundDriver(
        provider,
        deps.log,
        deps.upstream,
        deps.usageStore,
        failures,
        telemetry,
        TearAwareEvents(provider, deps.log),
    )
    private val ending = TurnEnding(provider, log, telemetry, failures, health)
    private val cancellationSeal = CancellationSeal(provider, log, telemetry, health)
    private val turnFinish = TurnFinish(clock, log, deps.usageStore, health, telemetry)

    // Pre-priced HD-24 contingency: collect() moved to its own file (CollectTurn.kt) because the
    // un-split TurnDriver.kt measured ratio 1.83, just over the 1.8 gate.
    private val collectTurn = CollectTurn(provider, driveFactory, wiring, this)

    /** G20: passive health snapshot for HeadServer.healthSnapshot() — the control-plane's
     *  /api/heads aggregation, never the per-head /health liveness route (external contract). */
    internal fun healthCounters(): HeadHealthCounts = health.snapshot()

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        val inputs = TurnInputs(built, slot, t0, perf)
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Flush-per-frame: a frame buffered across an upstream lull is invisible to the
            // user exactly when responsiveness matters (see ImmediateSseWriter header).
            val channel = ClientChannel(
                coalesced = ImmediateSseWriter(writeRaw = { frame -> write(frame) }, flushRaw = { flush() }),
                writeMutex = Mutex(),
                clientGone = AtomicBoolean(false),
            )
            val emitter = emitters.create(
                write = { frame ->
                    channel.writeMutex.withLock { channel.timedClientWrite(frame, perf, clock) }
                },
                model = built.meta.originalModel,
                usagePayload = wiring.usagePayloadBuilder(provider.catalog, built.meta),
            )
            val drive = driveFactory.assembleDrive(inputs, emitter, channel)
            try {
                // The 200 + SSE headers are committed once respondTextWriter opens, so any failure
                // must become an honest `event: error` frame — NOT escape and leave the client an
                // empty/truncated 200 (the "empty or malformed response (HTTP 200)" class).
                driveSealingCancellation(drive)
            } finally {
                // Terminal frames force-flush already; this covers abandon / exception paths.
                channel.coalesced.flush()
            }
        }
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
            failures.catchingTurnFailure { driveOneTurn(drive, pingClient) }
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

    // The turn coroutine is a CHILD job: the watchdog cancels just the turn subtree (then the
    // blocking Writer still lets the honest error frame out), while a client disconnect cancels
    // the PARENT call and propagates DOWN into the turn — a parentless Job() severed that, so
    // Esc'd turns kept streaming upstream and pinning gate slots until the watchdog cap
    // (the audit's top concurrency finding, 2026-07-18).
    private suspend fun driveOneTurn(drive: TurnDrive, pingClient: Boolean = true) {
        val parent = currentCoroutineContext()[Job]
        // CompletableJob completed in finally: a plain child Job never completes on its own and
        // would park the PARENT call forever after the turn returns.
        val turnJob = Job(parent)
        try {
            withContext(turnJob) {
                val self = this
                // Whole-turn client-liveness pinger (2026-07-19 storm): launched BEFORE the first
                // upstream attempt so the headers-wait (minutes on a long prefill) and the retry
                // backoffs are covered too — the per-attempt scope only started it after upstream
                // headers, so a client that hung up mid-retry left a zombie turn pinning its gate
                // slot and re-hammering the rate-limited account for a listener that was gone.
                // OFF for the non-stream collect path: there is no open SSE channel to ping (the
                // whole body is buffered and sent once), so liveness can't be probed mid-turn.
                val pinger = if (pingClient) {
                    drive.channel.launchClientPinger(self, turnJob, deps.ticker, provider.key, log)
                } else {
                    null
                }
                // NF-03: whole-turn totalCap poller, unconditional (non-stream turns burn wall
                // clock too). launchIn keeps the idle tiers stream-scoped; this one only samples
                // elapsed, so connect/backoff/refresh/between-rounds time finally counts.
                val capPoller = drive.watchdog.launchTotalCap(self, turnJob)
                try {
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
                } finally {
                    pinger?.cancel()
                    capPoller.cancel()
                }
            }
        } finally {
            turnJob.complete()
        }
    }

    /** Head restart = fresh diagnostic baseline (the HeadHealth doc's promised behavior; the
     *  counters lived through control-plane restarts before — review 2026-07-19). */
    internal fun resetHealth() = health.reset()
}
