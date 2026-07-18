// PORT-OF: server/src/usage/warn.mjs @ 4ca99f7 — invariants: PURE, zero heavy imports (the
// statusline endpoint and the control-server usage API share THIS logic, never forked);
// soft-warn NEVER blocks; ratelimit headers are the real signal, the 5h output-token count
// is the fallback (warnTokens5h = 0 disables it); critical at remaining<=0 or >=98% used.
package splice.core.usage

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

private const val CRITICAL_PCT = 98.0
private const val DEFAULT_WARN_PCT = 80
private const val FULL_PCT = 100.0

// the tier cascade is the ported contract: ratelimit headers first, then the 5h-token fallback,
// then the inert "ok". Each tier returns null to defer to the next.
public fun computeUsageWarn(
    outputTokens5h: Long = 0,
    ratelimit: RateLimitState? = null,
    warnPct: Int = DEFAULT_WARN_PCT,
    warnTokens5h: Long = 0,
): UsageWarn {
    val pctThreshold = if (warnPct > 0) warnPct else DEFAULT_WARN_PCT
    return ratelimitTierWarn(ratelimit, pctThreshold)
        ?: tokens5hTierWarn(outputTokens5h, warnTokens5h, pctThreshold)
        ?: UsageWarn("ok", 0, "none", null)
}

// ratelimit headers are the real signal. Null when the header pair is absent/unusable so the
// caller falls through to the next tier.
private fun ratelimitTierWarn(ratelimit: RateLimitState?, pctThreshold: Int): UsageWarn? {
    val limit = ratelimit?.limitTokens?.takeIf { it > 0 } ?: return null
    val remaining = ratelimit.remainingTokens ?: return null
    val usedPct = ((1.0 - remaining.toDouble() / limit) * FULL_PCT).coerceIn(0.0, FULL_PCT)
    val level = when {
        remaining <= 0 || usedPct >= CRITICAL_PCT -> "critical"
        usedPct >= pctThreshold -> "warn"
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
        usedPct >= pctThreshold -> "warn"
        else -> "ok"
    }
    return UsageWarn(level, usedPct, "tokens5h", null)
}
