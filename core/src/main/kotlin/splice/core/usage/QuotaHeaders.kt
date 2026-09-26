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
        val reset = h("$UNIFIED-$abbr-reset")?.toDoubleOrNull()?.let(EpochSeconds::of)
        return QuotaWindow(utilization * PERCENT, reset, seconds)
    }
}

/** V4-233: the upstream's own statement that a PLAN window is spent until a named instant. */
public data class PlanLimit(val claim: String, val resetEpochSeconds: Long) {
    /** The claim in words: `five_hour` is "5-hour", `seven_day` "7-day", and a model-scoped
     *  seven-day claim names its model (`seven_day_opus` is "7-day Opus"). */
    public val windowWords: String
        get() = when {
            claim == FIVE_HOUR_CLAIM -> "5-hour"
            claim == SEVEN_DAY_CLAIM -> "7-day"
            claim.startsWith("${SEVEN_DAY_CLAIM}_") ->
                "7-day " + claim.removePrefix("${SEVEN_DAY_CLAIM}_").split('_').joinToString(" ") { part ->
                    part.replaceFirstChar { it.uppercaseChar() }
                }
            else -> claim
        }
}

/** V4-233: Anthropic's unified family names a spent plan window in three members: `-status:
 *  rejected`, a `-representative-claim` naming the window that refused, and the plain `-reset`.
 *  That combination is a spent window, not a burst: it clears at the reset and not before, so a
 *  re-send before it cannot succeed. The claims are the ones the 2.1.282 client knows: `five_hour`,
 *  and the seven-day family (`seven_day`, `seven_day_opus`, `seven_day_sonnet` and the rest).
 *  `overage` is a spend bucket, not a window, and every other shape is null, which leaves V4-61's
 *  handling of a burst 429 alone (muse stamps its window on bursts and sends no unified family).
 *
 *  The reset is BOUNDED by the claim's own window: an instant further out than one whole window
 *  from `nowEpochSeconds` is not one this claim can name, and a malformed one must not hold a head
 *  past it. A reset already passed names nothing. */
internal object PlanLimits {
    fun of(header: QuotaHeaderRead, nowEpochSeconds: Long): PlanLimit? {
        val claim = header("$UNIFIED-representative-claim")
            ?.takeIf { header("$UNIFIED-status") == QuotaStatus.REJECTED.wire }
            ?: return null
        val window = windowSeconds(claim) ?: return null
        val reset = header("$UNIFIED-reset")?.toDoubleOrNull()?.let(EpochSeconds::of)?.takeIf { it > nowEpochSeconds }
        return reset?.let { PlanLimit(claim, minOf(it, nowEpochSeconds + window)) }
    }

    /** How long the window a unified claim names lasts; null for a claim that names no window. */
    private fun windowSeconds(claim: String): Long? = when {
        claim == FIVE_HOUR_CLAIM -> FIVE_HOURS_SECONDS
        claim.startsWith(SEVEN_DAY_CLAIM) -> SEVEN_DAYS_SECONDS
        else -> null
    }
}

/** Providers disagree on seconds vs millis; anything past [EPOCH_MILLIS_FLOOR] — the year 5138
 *  read as seconds, March 1973 read as millis — is millis. The comparison converts for free:
 *  Kotlin compares a Double against a Long directly, so the shared constant stays one Long and
 *  no site restates it in its own numeric type. One reading for the quota windows and the plan
 *  limit alike, which is why it is its own unit (V4-233). */
internal object EpochSeconds {
    fun of(value: Double): Long = if (value > EPOCH_MILLIS_FLOOR) (value / MILLIS).toLong() else value.toLong()
}

private const val UNIFIED = "anthropic-ratelimit-unified"
private const val FIVE_HOUR_CLAIM = "five_hour"
private const val SEVEN_DAY_CLAIM = "seven_day"
private const val PERCENT = 100.0
private const val MILLIS = 1000L

/** V4-122: the seconds-versus-milliseconds discriminator for a quota reset timestamp, declared
 *  ONCE. Four files carried this name across three different values, which the checker held as a
 *  scar because disagreement here silently mis-scales a reset time by 1000x — a bar that says six
 *  days when the plan resets in nine minutes.
 *
 *  THE VALUE IS NOT A JUDGMENT CALL, which is why it could be unified from the code rather than
 *  from a preference: read in SECONDS this threshold is the year 5138, in MILLISECONDS it is March
 *  1973, so a real timestamp in either scale sits decades from the edge. This file's old
 *  10_000_000_000.0 was the outlier — the same semantics with the comparison inverted, and the only
 *  value close enough to a live timestamp for the direction of the test to matter. */
public const val EPOCH_MILLIS_FLOOR: Long = 100_000_000_000
