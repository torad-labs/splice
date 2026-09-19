// NEW: per-turn RunnerSignals construction, split from TurnDriveFactory
// (concentration, 2026-08-19) so the factory is not billed for splice.gateway.round.
// Same-package.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.LogSink
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.ClientChannel
import splice.spi.Provider
import splice.spi.TurnWatchdog
import splice.spi.WatchdogFired

private const val ROUND_FAILURE_SNIPPET = 160

internal class DriveSignals(
    private val provider: Provider,
    /** The ONE thing this collaborator needs from the head (V4-105 item 3). It took the whole
     *  [HeadDeps] to reach `deps.log`, which meant every change anywhere in that 25-parameter bundle
     *  reported this file as a caller — the coupling was to the bundle, not to the seam. */
    private val log: LogSink,
    private val health: HeadHealthCounters,
) {
    fun make(watchdog: TurnWatchdog, channel: ClientChannel, perf: TurnPerf): RunnerSignals =
        RunnerSignals(
            watchdogFired = { watchdog.fired != null },
            clientGone = { channel.clientGone.get() },
            onRoundFailure = { f ->
                // Absorbed round failures still count for the G20 health split (code-review 2026-07-24).
                log(
                    "[${provider.key}] mid-stream ${f.type.wireName} absorbed by " +
                        "re-anchor: ${f.message.take(ROUND_FAILURE_SNIPPET)}\n",
                )
                if (f.providerReported) health.provider() else health.local()
            },
            onSearchRound = { perf.setCount(PerfKeys.SEARCH_ROUNDS, it.toLong()) },
            onReanchor = {
                // V4-116 (5), THE EVIDENCE ROW. Two numbers, because neither is interpretable
                // alone: one POST after nine silent minutes and five POSTs after twenty seconds
                // each are opposite diagnoses, and only the pair tells them apart.
                //
                // The silence is read HERE rather than passed in, because this is the one place
                // that holds the watchdog — see ReanchorSpentHook for why the runner must not be
                // taught a fact it cannot see.
                perf.add(PerfKeys.REANCHORS, 1)
                // `as?` on purpose: a re-anchor that was NOT triggered by the watchdog (a tear
                // converted by SseRoundDriver.tearOutcome, or a provider-reported failure) has no
                // silence to report, and stamping 0 would claim a stall that never happened.
                (watchdog.fired as? WatchdogFired.Idle)?.let { perf.add(PerfKeys.STALL_MS, it.idleMs) }
            },
        )
}
