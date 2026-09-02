// NEW: the per-turn job/pinger/totalCap envelope around RoundStrategy.
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import splice.spi.Provider

internal class TurnOneDrive(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val roundRun: TurnRoundRun,
) {
    // The turn coroutine is a CHILD job: the watchdog cancels just the turn subtree (then the
    // blocking Writer still lets the honest error frame out), while a client disconnect cancels
    // the PARENT call and propagates DOWN into the turn — a parentless Job() severed that, so
    // Esc'd turns kept streaming upstream and pinning gate slots until the watchdog cap
    // (the audit's top concurrency finding, 2026-07-18).
    suspend fun driveOneTurn(drive: TurnDrive, pingClient: Boolean = true) {
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
                    drive.channel.launchClientPinger(self, turnJob, deps.ticker, provider.key, deps.log)
                } else {
                    null
                }
                // NF-03: whole-turn totalCap poller, unconditional (non-stream turns burn wall
                // clock too). launchIn keeps the idle tiers stream-scoped; this one only samples
                // elapsed, so connect/backoff/refresh/between-rounds time finally counts.
                val capPoller = drive.watchdog.launchTotalCap(self, turnJob)
                try {
                    roundRun.run(drive, self, turnJob)
                } finally {
                    pinger?.cancel()
                    capPoller.cancel()
                }
            }
        } finally {
            turnJob.complete()
        }
    }
}
