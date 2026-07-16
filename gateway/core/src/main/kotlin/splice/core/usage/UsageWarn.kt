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

@Suppress("CyclomaticComplexMethod", "ComplexCondition") // the tier cascade is the ported contract
public fun computeUsageWarn(
    outputTokens5h: Long = 0,
    ratelimit: RateLimitState? = null,
    warnPct: Int = DEFAULT_WARN_PCT,
    warnTokens5h: Long = 0,
): UsageWarn {
    val pctThreshold = if (warnPct > 0) warnPct else DEFAULT_WARN_PCT
    val limit = ratelimit?.limitTokens
    val remaining = ratelimit?.remainingTokens

    if (limit != null && limit > 0 && remaining != null) {
        val usedPct = ((1.0 - remaining.toDouble() / limit) * FULL_PCT).coerceIn(0.0, FULL_PCT)
        val level = when {
            remaining <= 0 || usedPct >= CRITICAL_PCT -> "critical"
            usedPct >= pctThreshold -> "warn"
            else -> "ok"
        }
        return UsageWarn(level, usedPct.roundToInt(), "ratelimit", ratelimit.resetTokens)
    }

    if (warnTokens5h > 0) {
        val usedPct = ((outputTokens5h.toDouble() / warnTokens5h) * FULL_PCT).coerceAtMost(FULL_PCT).roundToInt()
        val level = when {
            outputTokens5h >= warnTokens5h -> "critical"
            usedPct >= pctThreshold -> "warn"
            else -> "ok"
        }
        return UsageWarn(level, usedPct, "tokens5h", null)
    }

    return UsageWarn("ok", 0, "none", null)
}
