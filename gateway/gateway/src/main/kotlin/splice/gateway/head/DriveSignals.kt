// NEW: per-turn RunnerSignals construction, split from TurnDriveFactory
// (concentration, 2026-08-19) so the factory is not billed for splice.gateway.round.
// Same-package.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.ClientChannel
import splice.spi.Provider
import splice.spi.TurnWatchdog

private const val ROUND_FAILURE_SNIPPET = 160

internal class DriveSignals(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val health: HeadHealthCounters,
) {
    fun make(watchdog: TurnWatchdog, channel: ClientChannel, perf: TurnPerf): RunnerSignals =
        RunnerSignals(
            watchdogFired = { watchdog.fired != null },
            clientGone = { channel.clientGone.get() },
            onRoundFailure = { f ->
                // Absorbed round failures still count for the G20 health split (code-review 2026-07-24).
                deps.log(
                    "[${provider.key}] mid-stream ${f.type.wireName} absorbed by " +
                        "re-anchor: ${f.message.take(ROUND_FAILURE_SNIPPET)}\n",
                )
                if (f.providerReported) health.provider() else health.local()
            },
            onSearchRound = { perf.setCount(PerfKeys.SEARCH_ROUNDS, it.toLong()) },
        )
}
