// NEW: the per-turn job/pinger/totalCap envelope around RoundStrategy.
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.turn

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import splice.core.perf.PerfKeys
import splice.core.turn.HeadStopSignal
import splice.head.HeadDeps
import splice.head.wire.Heartbeat
import splice.head.wire.LostClient
import splice.upstream.Provider
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import splice.upstream.transport.UpstreamEnding

internal class TurnOneDrive(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val roundRun: TurnRoundRun,
    /** V4-456: the frame tick paced output is released on (ClientChannel.launchPacer). Not
     *  [HeadDeps.HeadSeams.ticker]: that is the client pinger's two-second liveness cadence, and a test
     *  that feeds the pinger its ticks one at a time must not have the pacer consume them — a paced tail
     *  waiting on a tick nobody sends holds the turn's admission slot after message_stop. */
    private val paceTicker: Ticker = ProcessTicker(),
) {
    private val lifecycle = Any()
    private var stopSignal = HeadStopSignal()
    private val activeJobs = mutableSetOf<Job>()

    /** Stop unfinished child turns before the engine closes their client sockets. */
    fun stopActive() {
        val jobs = synchronized(lifecycle) {
            stopSignal.stop()
            activeJobs.toList()
        }
        jobs.forEach { it.cancel(HeadRestart()) }
    }

    /** The same driver is used after a head restart; new turns may run again. */
    fun headStarted() {
        synchronized(lifecycle) {
            if (stopSignal.isStopping) stopSignal = HeadStopSignal()
        }
    }

    // The turn coroutine is a CHILD job: the watchdog cancels just the turn subtree (then the
    // blocking Writer still lets the honest error frame out), while a client disconnect cancels
    // the PARENT call and propagates DOWN into the turn — a parentless Job() severed that, so
    // Esc'd turns kept streaming upstream and pinning gate slots until the watchdog cap
    // (the audit's top concurrency finding, 2026-07-18).
    /** Null when the turn ran to its own terminal; otherwise the ending its last round had, for the caller to write
     *  once the pinger, the pacer and the cap poller below have stopped (the order an exception took before). */
    suspend fun driveOneTurn(drive: TurnDrive, pingClient: Boolean = true): UpstreamEnding? {
        // CompletableJob completed in finally: a plain child Job never completes on its own and
        // would park the PARENT call forever after the turn returns.
        val owner = synchronized(lifecycle) { stopSignal }
        val turnJob = newTurnJob(owner)
        // V4-319: the operator's stop cancels exactly this job, the one the watchdog cancels (LiveTurns).
        deps.traffic.liveTurns.driving(drive.slot, turnJob)
        // Per TURN: the line remembers whether it has spoken, so the first one explains itself, and
        // counts the heartbeats of the current quiet stretch to thin its lines out.
        val progress = TurnProgressLine()
        return try {
            withContext(turnJob + owner) {
                val self = this
                // Whole-turn client-liveness pinger (2026-07-19 storm): launched BEFORE the first
                // upstream attempt so the headers-wait (minutes on a long prefill) and the retry
                // backoffs are covered too — the per-attempt scope only started it after upstream
                // headers, so a client that hung up mid-retry left a zombie turn pinning its gate
                // slot and re-hammering the rate-limited account for a listener that was gone.
                // OFF for the non-stream collect path: there is no open SSE channel to ping (the
                // whole body is buffered and sent once), so liveness can't be probed mid-turn.
                val pinger = if (pingClient) {
                    drive.channel.launchClientPinger(
                        self,
                        turnJob,
                        deps.seams.ticker,
                        LostClient(provider.key, deps.log, drive.sessionTag()),
                        // The pinger's two frames (ClientChannel.HEARTBEAT_EVERY_TICKS): the ping
                        // that re-arms the client's stall watchdog, and — unless the operator turned
                        // it off — the status line that makes the wait visible instead of blank.
                        heartbeat = Heartbeat {
                            drive.emitter.heartbeat()
                            if (deps.policy.progressLine) {
                                // Composed only if the emitter actually writes it: a line built for
                                // a dropped write spends the intro and the clock reading with it.
                                drive.emitter.progress { fresh ->
                                    progress.next(
                                        elapsedMs = deps.seams.clock() - drive.t0,
                                        model = drive.upstreamModel,
                                        sawOutput = drive.perf.hasMark(PerfKeys.FIRST_DELTA),
                                        fresh = fresh,
                                    )
                                }
                            }
                        },
                    )
                } else {
                    null
                }
                // NF-03: whole-turn totalCap poller, unconditional (non-stream turns burn wall
                // clock too). launchIn keeps the idle tiers stream-scoped; this one only samples
                // elapsed, so connect/backoff/refresh/between-rounds time finally counts.
                // V4-456: streaming only, like the pinger — the collect path has no wire to pace.
                val pacing = if (pingClient) launchPacing(drive, self, turnJob) else null
                val capPoller = drive.watchdog.launchTotalCap(self, turnJob)
                try {
                    roundRun.run(drive, self, turnJob).also {
                        pacing?.let { drive.channel.finishPacing(it, deps.seams.clock) }
                    }
                } finally {
                    pacing?.cancel()
                    pinger?.cancel()
                    capPoller.cancel()
                }
            }
        } finally {
            synchronized(lifecycle) { activeJobs.remove(turnJob) }
            turnJob.complete()
        }
    }

    private suspend fun newTurnJob(owner: HeadStopSignal): CompletableJob {
        val job = Job(currentCoroutineContext()[Job])
        val cut = synchronized(lifecycle) {
            activeJobs += job
            owner.isStopping
        }
        if (cut) job.cancel(HeadRestart())
        return job
    }

    /** V4-456: a provider batch (a whole thinking summary in one read) reaches the client spread over the
     *  pacing window instead of in one frame. driveOneTurn waits for the paced tail before it returns
     *  (ClientChannel.finishPacing), so the response never closes on a held frame. */
    private fun launchPacing(drive: TurnDrive, scope: CoroutineScope, turnJob: Job): Job =
        drive.channel.launchPacer(
            scope,
            turnJob,
            paceTicker,
            deps.seams.clock,
            LostClient(provider.key, deps.log, drive.sessionTag()),
        )
}
