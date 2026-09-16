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

/** The three values `anthropic-ratelimit-unified-status` may carry. Taken from the client binary
 *  (claude 2.1.257), which matches on all three and carries the literal `unified-status: rejected`
 *  — so splice asserting `allowed` unconditionally was not a client limitation but ours. */
public enum class QuotaStatus(public val wire: String) {
    ALLOWED("allowed"),
    WARNING("allowed_warning"),
    REJECTED("rejected"),
}

public class QuotaHeaders(private val clock: WallClock) {
    /** The headers Claude Code reads. Empty for an empty snapshot; carries `-status: allowed` with
     *  any window because the client keys its warning state off that header too.
     *
     *  [status] and [resetEpochSeconds] exist so a REFUSAL can be stated on the same family — and
     *  the DEFAULT is deliberately today's exact bytes, so every response that passes neither
     *  parameter is unchanged down to the key order. Passing [QuotaStatus.ALLOWED] explicitly is
     *  NOT the same as passing nothing when the snapshot is empty: an explicit status is always
     *  written, because a refusal has to be stated even when no window is known.
     *
     *  [resetEpochSeconds] writes the PLAIN `anthropic-ratelimit-unified-reset`, which is a
     *  different member from the per-window `-5h-reset` and `-7d-reset` above and is the one Claude
     *  Code's withRetry actually reads off a 429 (getRateLimitResetDelayMs). WHY A PLAIN RESET
     *  EXISTS AT ALL: a 429 is not a quota bar, it is a DEADLINE. The window members describe how
     *  full a bucket is; this one says when to come back, and a client that has just been refused
     *  needs the second and not the first. */
    public fun forClient(
        snapshot: QuotaSnapshot,
        status: QuotaStatus? = null,
        resetEpochSeconds: Long? = null,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clientWindow(out, "5h", snapshot.fiveHour)
        clientWindow(out, "7d", snapshot.sevenDay)
        if (status != null) {
            out["$UNIFIED-status"] = status.wire
        } else if (out.isNotEmpty()) {
            out["$UNIFIED-status"] = QuotaStatus.ALLOWED.wire
        }
        resetEpochSeconds?.let { out["$UNIFIED-reset"] = it.toString() }
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
