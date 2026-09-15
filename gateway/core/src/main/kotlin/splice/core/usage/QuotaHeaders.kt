// NEW: quota windows on the wire, both directions. TOWARD the client: the anthropic-ratelimit-
// unified-{5h,7d}-{utilization,reset} family Claude Code reads into its `rate_limits` (utilization
// as a 0..1 fraction, reset as epoch seconds), which is what its own status line and /usage draw.
// Splice writes every response Claude Code sees, so a Codex, Kimi or Grok head can carry the same
// headers Anthropic sends, and the client shows the head's real windows without knowing there is a
// proxy. FROM the upstream: Anthropic's own unified family on a passthrough head. Vendor families
// (x-codex-*) live behind QuotaHeaderFamily in the owning provider module.
package splice.core.usage

import splice.core.util.WallClock
import java.util.Locale

/** One upstream response header by name, or null. The gateway's own HeaderLookup lives a module
 *  above this one, so the header families are decoded against this port. */
public fun interface QuotaHeaderRead {
    public operator fun invoke(name: String): String?
}

public class QuotaHeaders(private val clock: WallClock) {
    /** The headers Claude Code reads. Empty for an empty snapshot; carries `-status: allowed` with
     *  any window because the client keys its warning state off that header too. */
    public fun forClient(snapshot: QuotaSnapshot): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clientWindow(out, "5h", snapshot.fiveHour)
        clientWindow(out, "7d", snapshot.sevenDay)
        if (out.isNotEmpty()) out["$UNIFIED-status"] = "allowed"
        return out
    }

    private fun clientWindow(out: MutableMap<String, String>, abbr: String, w: QuotaWindow?) {
        val window = w ?: return
        val reset = window.resetsAt ?: (clock() / MILLIS + (window.windowSeconds ?: 0L))
        out["$UNIFIED-$abbr-utilization"] = String.format(Locale.ROOT, "%.4f", window.usedPercent / PERCENT)
        out["$UNIFIED-$abbr-reset"] = reset.toString()
    }

    /** Anthropic's unified family; null when the response does not carry it. */
    public fun fromUpstream(header: QuotaHeaderRead): QuotaSnapshot? = unified(header)

    private fun unified(h: QuotaHeaderRead): QuotaSnapshot? {
        val five = unifiedWindow(h, "5h", FIVE_HOURS_SECONDS)
        val seven = unifiedWindow(h, "7d", SEVEN_DAYS_SECONDS)
        return if (five == null && seven == null) null else QuotaSnapshot(five, seven, null, clock())
    }

    private fun unifiedWindow(h: QuotaHeaderRead, abbr: String, seconds: Long): QuotaWindow? {
        val utilization = h("$UNIFIED-$abbr-utilization")?.toDoubleOrNull() ?: return null
        val reset = h("$UNIFIED-$abbr-reset")?.toDoubleOrNull()?.let(::epochSeconds)
        return QuotaWindow(utilization * PERCENT, reset, seconds)
    }

    /** Providers disagree on seconds vs millis; anything past year 2286 in seconds is millis. */
    private fun epochSeconds(value: Double): Long =
        if (value > EPOCH_MILLIS_FLOOR) (value / MILLIS).toLong() else value.toLong()
}

private const val UNIFIED = "anthropic-ratelimit-unified"
private const val PERCENT = 100.0
private const val MILLIS = 1000L
private const val EPOCH_MILLIS_FLOOR = 10_000_000_000.0
