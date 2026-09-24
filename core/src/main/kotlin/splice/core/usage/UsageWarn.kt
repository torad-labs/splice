// PORT-OF: server/src/usage/warn.mjs @ pre-public-port-baseline — invariants: PURE, zero heavy imports (the
// statusline endpoint and the control-server usage API share THIS logic, never forked);
// soft-warn NEVER blocks; ratelimit headers are the real signal, the 5h output-token count
// is the fallback (warnTokens5h = 0 disables it); critical at remaining<=0 or >=98% used.
package splice.core.usage

import splice.core.config.Knob
import java.time.Instant
import kotlin.math.roundToInt

public data class RateLimitState(
    val limitTokens: Long?,
    val remainingTokens: Long?,
    val resetTokens: String?,
    val updatedAt: Long? = null,
)

public data class UsageWarn(
    val level: String,
    val pct: Int,
    val source: String,
    val reset: String?,
)

/** The head's plan windows as read at [nowEpochSeconds] — the policy stays PURE, so the caller
 *  brings the clock that decides which windows have already reset. */
public data class PlanWindows(val quota: QuotaView, val nowEpochSeconds: Long)

private const val CRITICAL_PCT = 98.0
private const val FULL_PCT = 100.0

private const val LEVEL_OK = "ok"
private const val LEVEL_WARN = "warn"
private const val LEVEL_CRITICAL = "critical"

// the levels in rising severity; the worse of two real signals is the later one here
private val LEVELS = listOf(LEVEL_OK, LEVEL_WARN, LEVEL_CRITICAL)

/** The tier cascade, as a named object since the 2026-08-16 style migration (HD-M8) — PURE and
 *  stateless, exactly as the ported contract requires; the statusline endpoint and the control-server
 *  usage API still share THIS logic and never fork it. */
public object UsageWarnPolicy {

    // the tier cascade is the ported contract: the real signals first — ratelimit headers and the
    // plan windows, the worse of the two when both report — then the 5h-token fallback, then the
    // inert "ok". Each tier returns null to defer to the next.
    /** [warnPct] is the `usageWarnPct` knob: the percentage of a plan window at which the level
     *  becomes `warn`. V4-109: **0 DISABLES the warn tier**, it does not fall back to a default —
     *  an operator who writes 0 is asking for silence, and answering with 80 is the config layer
     *  lying about the value it read. `critical` still fires, because that is the hard edge
     *  (`remaining <= 0` or >=98% used), not a percentage preference. The parameter's default
     *  READS the knob rather than restating its literal, so the one operator-facing source of 80
     *  stays Knob.USAGE_WARN_PCT and the two cannot drift (const-single-source's KNOB-SHADOW).
     *
     *  [plan] is the head's plan windows (console review 2026-09-24): a subscription head carries
     *  no ratelimit token headers, so before this tier its warn read `ok`/`none` at 99% of a 7d
     *  window and the knob that says "plan window" never touched one. */
    public fun computeUsageWarn(
        outputTokens5h: Long = 0,
        ratelimit: RateLimitState? = null,
        warnPct: Int = (Knob.USAGE_WARN_PCT.default as Long).toInt(),
        warnTokens5h: Long = 0,
        plan: PlanWindows? = null,
    ): UsageWarn =
        listOfNotNull(ratelimitTierWarn(ratelimit, warnPct), planTierWarn(plan, warnPct))
            .maxWithOrNull(compareBy({ LEVELS.indexOf(it.level) }, { it.pct }))
            ?: tokens5hTierWarn(outputTokens5h, warnTokens5h, warnPct)
            ?: UsageWarn(LEVEL_OK, 0, "none", null)

    // A window whose resets_at has passed is spent history, not a limit: its used_pct is the last
    // reading before the reset, and a head that has not turned since still reports it. Of the live
    // windows the fuller one is the nearer limit; its source names which (`quota_5h` / `quota_7d`)
    // and its reset is the ISO-8601 instant the window turns over.
    private fun planTierWarn(plan: PlanWindows?, pctThreshold: Int): UsageWarn? {
        val (source, window) = plan?.let(::fullestLiveWindow) ?: return null
        val usedPct = window.usedPct.coerceIn(0, FULL_PCT.toInt())
        val reset = window.resetsAt?.let { Instant.ofEpochSecond(it).toString() }
        return UsageWarn(level(usedPct >= CRITICAL_PCT, usedPct, pctThreshold), usedPct, source, reset)
    }

    private fun fullestLiveWindow(plan: PlanWindows): Pair<String, QuotaWindowView>? =
        listOfNotNull(plan.quota.fiveHour?.let { "quota_5h" to it }, plan.quota.sevenDay?.let { "quota_7d" to it })
            .filter { (_, window) -> window.resetsAt?.let { it > plan.nowEpochSeconds } ?: true }
            .maxByOrNull { (_, window) -> window.usedPct }

    // ratelimit headers are the real signal. Null when the header pair is absent/unusable so the
    // caller falls through to the next tier.
    private fun ratelimitTierWarn(ratelimit: RateLimitState?, pctThreshold: Int): UsageWarn? {
        val limit = ratelimit?.limitTokens?.takeIf { it > 0 } ?: return null
        val remaining = ratelimit.remainingTokens ?: return null
        val usedPct = ((1.0 - remaining.toDouble() / limit) * FULL_PCT).coerceIn(0.0, FULL_PCT)
        val level = level(remaining <= 0 || usedPct >= CRITICAL_PCT, usedPct, pctThreshold)
        return UsageWarn(level, usedPct.roundToInt(), "ratelimit", ratelimit.resetTokens)
    }

    // the 5h output-token count is the fallback (warnTokens5h = 0 disables it). Null defers to "ok".
    private fun tokens5hTierWarn(outputTokens5h: Long, warnTokens5h: Long, pctThreshold: Int): UsageWarn? {
        if (warnTokens5h <= 0) return null
        val usedPct = ((outputTokens5h.toDouble() / warnTokens5h) * FULL_PCT).coerceAtMost(FULL_PCT).roundToInt()
        return UsageWarn(level(outputTokens5h >= warnTokens5h, usedPct, pctThreshold), usedPct, "tokens5h", null)
    }

    // one edge for every tier: `critical` when the tier's window is spent (the hard edge, which a
    // warnPct of 0 never silences), `warn` at the knob's share, else `ok`
    private fun level(spent: Boolean, usedPct: Number, pctThreshold: Int): String = when {
        spent -> LEVEL_CRITICAL
        pctThreshold > 0 && usedPct.toDouble() >= pctThreshold -> LEVEL_WARN
        else -> LEVEL_OK
    }
}
