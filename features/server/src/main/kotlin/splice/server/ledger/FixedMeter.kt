// NEW: server mode (docs/specs/server-mode.md, F10 fixed-cost providers). What the providers paid for by the hour cost
// over a report's window: the hours the host was up inside it, from its boot time as the kernel says it when the report
// is made, times each provider's `hourly_usd`. The host is the box the server runs on, the one rented by the hour; it
// bills whether or not anyone sends it a turn, and a daemon restart does not restart its meter. A window that reaches
// past the report's own moment is metered up to that moment, and one from before the host's last boot meters nothing
// before it, since no earlier boot can be read.
package splice.server.ledger

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration

/** One provider or external meter paid by the hour, over a report's window. */
internal data class FixedCost(
    val provider: String,
    val hours: Double,
    val costUsd: Double,
    val external: Boolean = false,
) {
    /** As a report carries it, with a source marker only on external meters. */
    fun json(): JsonObject = buildJsonObject {
        put("provider", provider)
        put("hours", hours)
        put("cost_usd", costUsd)
        if (external) put("source", "external")
    }
}

/** Meters the hours from when [boot] says the host came up to the moment [clock] reads when a report is made. */
internal class FixedMeter(private val boot: HostBoot, private val clock: Clock) {
    /** What each of [prices] (a provider's cost an hour in USD, by its key) cost over [window], in their order; none
     *  for a server with no hourly price. */
    fun of(prices: Map<String, Double>, window: UsageWindow): List<FixedCost> {
        if (prices.isEmpty()) return emptyList()
        val hours = upHours(window)
        return prices.map { (provider, hourly) -> FixedCost(provider, hours, hours * hourly) }
    }

    /** The hours of [window] the host was up: from its boot, or the window's start when it booted earlier, up to now,
     *  or the window's end when that is earlier. */
    private fun upHours(window: UsageWindow): Double {
        val start = maxOf(boot.at(), window.from)
        val end = minOf(clock.instant(), window.to)
        val up = Duration.between(start, end).coerceAtLeast(Duration.ZERO)
        return up.toMillis().toDouble() / Duration.ofHours(1).toMillis()
    }
}
