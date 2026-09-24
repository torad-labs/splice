// PORT-OF: server/src/usage/warn.mjs @ pre-public-port-baseline — invariants: PURE, zero heavy imports (the
// statusline endpoint and the control-server usage API share THIS logic, never forked);
// soft-warn NEVER blocks; ratelimit headers are the real signal, the 5h output-token count
// is the fallback (warnTokens5h = 0 disables it); critical at remaining<=0 or >=98% used.
package splice.core.usage

import splice.core.config.Knob
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

public data class RateLimitState(
    val limitTokens: Long?,
    val remainingTokens: Long?,
    val resetTokens: String?,
    /** Epoch millis of the round whose headers these are; null when the file predates the field. */
    val updatedAt: Long? = null,
) {
    /** [updatedAt] in epoch SECONDS, the unit every quota `resets_at` carries, so the control plane
     *  prints one observation encoding. Null when nothing names the observation. */
    public val observedAtEpochSeconds: Long?
        get() = updatedAt?.takeIf { it > 0L }?.let(TimeUnit.MILLISECONDS::toSeconds)
}

public data class UsageWarn(
    val level: String,
    val pct: Int,
    val source: String,
    val reset: String?,
)

private const val CRITICAL_PCT = 98.0
private const val FULL_PCT = 100.0

/** The tier cascade, as a named object since the 2026-08-16 style migration (HD-M8) — PURE and
 *  stateless, exactly as the ported contract requires; the statusline endpoint and the control-server
 *  usage API still share THIS logic and never fork it. */
public object UsageWarnPolicy {

    // the tier cascade is the ported contract: ratelimit headers first, then the 5h-token fallback,
    // then the inert "ok". Each tier returns null to defer to the next.
    /** [warnPct] is the `usageWarnPct` knob: the percentage of a plan window at which the level
     *  becomes `warn`. V4-109: **0 DISABLES the warn tier**, it does not fall back to a default —
     *  an operator who writes 0 is asking for silence, and answering with 80 is the config layer
     *  lying about the value it read. `critical` still fires, because that is the hard edge
     *  (`remaining <= 0` or >=98% used), not a percentage preference. The parameter's default
     *  READS the knob rather than restating its literal, so the one operator-facing source of 80
     *  stays Knob.USAGE_WARN_PCT and the two cannot drift (const-single-source's KNOB-SHADOW). */
    public fun computeUsageWarn(
        outputTokens5h: Long = 0,
        ratelimit: RateLimitState? = null,
        warnPct: Int = (Knob.USAGE_WARN_PCT.default as Long).toInt(),
        warnTokens5h: Long = 0,
    ): UsageWarn =
        ratelimitTierWarn(ratelimit, warnPct)
            ?: tokens5hTierWarn(outputTokens5h, warnTokens5h, warnPct)
            ?: UsageWarn("ok", 0, "none", null)

    // ratelimit headers are the real signal. Null when the header pair is absent/unusable so the
    // caller falls through to the next tier.
    private fun ratelimitTierWarn(ratelimit: RateLimitState?, pctThreshold: Int): UsageWarn? {
        val limit = ratelimit?.limitTokens?.takeIf { it > 0 } ?: return null
        val remaining = ratelimit.remainingTokens ?: return null
        val usedPct = ((1.0 - remaining.toDouble() / limit) * FULL_PCT).coerceIn(0.0, FULL_PCT)
        val level = when {
            remaining <= 0 || usedPct >= CRITICAL_PCT -> "critical"
            pctThreshold > 0 && usedPct >= pctThreshold -> "warn"
            else -> "ok"
        }
        return UsageWarn(level, usedPct.roundToInt(), "ratelimit", ratelimit.resetTokens)
    }

    // the 5h output-token count is the fallback (warnTokens5h = 0 disables it). Null defers to "ok".
    private fun tokens5hTierWarn(outputTokens5h: Long, warnTokens5h: Long, pctThreshold: Int): UsageWarn? {
        if (warnTokens5h <= 0) return null
        val usedPct = ((outputTokens5h.toDouble() / warnTokens5h) * FULL_PCT).coerceAtMost(FULL_PCT).roundToInt()
        val level = when {
            outputTokens5h >= warnTokens5h -> "critical"
            pctThreshold > 0 && usedPct >= pctThreshold -> "warn"
            else -> "ok"
        }
        return UsageWarn(level, usedPct, "tokens5h", null)
    }
}
