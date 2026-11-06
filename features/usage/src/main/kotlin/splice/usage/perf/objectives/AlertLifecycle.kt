package splice.usage.perf.objectives

import kotlinx.serialization.Serializable

@Serializable
internal data class AlertState(
    val ruleId: String,
    val revision: String,
    val state: String = "insufficient_data",
    val active: Boolean = false,
    val alertId: String? = null,
    val pendingSince: Long? = null,
    val firingSince: Long? = null,
    val resolvedAt: Long? = null,
    val recoverySince: Long? = null,
    val lastEvaluation: Long? = null,
    val reason: String? = null,
    val lastWindowEnd: Long? = null,
    val lastFreshEvaluation: Long? = null,
    val heldMs: Long = 0,
    val recoveredMs: Long = 0,
)

internal object AlertLifecycle {
    fun advance(
        previous: AlertState,
        rule: AlertRule,
        measurement: AlertMeasurement,
        now: Long,
        restarted: Boolean,
    ): AlertState {
        val gap = continuityGap(previous, measurement, rule.windowMs, now)
        val prior = if (restarted || gap) reset(previous) else previous
        val elapsed = elapsed(prior, measurement, now)
        val next = when {
            measurement.reason == COMPLETED_UNCHANGED -> prior.copy(
                state = "insufficient_data",
                reason = measurement.reason,
            )
            measurement.reason != null -> reset(prior).copy(
                state = "insufficient_data",
                reason = measurement.reason,
            )
            prior.active -> active(prior, rule, requireNotNull(measurement.observed), now, elapsed)
            rule.breached(requireNotNull(measurement.observed)) -> pending(prior, rule, now, elapsed)
            else -> reset(prior).copy(
                state = "resolved",
                reason = "healthy_measurement",
            )
        }
        return remember(next, measurement, now)
    }

    fun changed(current: AlertState, previous: AlertState): Boolean = withoutProgress(
        current,
    ) != withoutProgress(previous)

    private fun withoutProgress(state: AlertState): AlertState = state.copy(
        lastEvaluation = null,
        lastWindowEnd = null,
        lastFreshEvaluation = null,
        heldMs = 0,
        recoveredMs = 0,
    )

    private fun reset(state: AlertState): AlertState = state.copy(
        pendingSince = null,
        recoverySince = null,
        heldMs = 0,
        recoveredMs = 0,
        lastFreshEvaluation = null,
    )

    private fun continuityGap(prior: AlertState, measurement: AlertMeasurement, windowMs: Long, now: Long): Boolean {
        val window = measurement.timing?.window
        val completed = window?.basis == COMPLETED_REQUEST_WINDOW
        val last = if (completed) prior.lastFreshEvaluation ?: prior.lastEvaluation else prior.lastEvaluation
        val dataGap = if (completed) window.until?.let { until -> prior.lastWindowEnd?.let { until - it } } else null
        return last?.let { now - it > windowMs } == true || dataGap?.let { it > windowMs } == true
    }

    private fun elapsed(prior: AlertState, measurement: AlertMeasurement, now: Long): Long {
        val wall = prior.lastFreshEvaluation?.let { (now - it).coerceAtLeast(0) } ?: 0
        val window = measurement.timing?.window
        val advance = window?.until?.let { until -> prior.lastWindowEnd?.let { until - it } } ?: 0
        return if (window?.basis == COMPLETED_REQUEST_WINDOW) minOf(wall, advance.coerceAtLeast(0)) else wall
    }

    private fun remember(state: AlertState, measurement: AlertMeasurement, now: Long): AlertState {
        val window = measurement.timing?.window
        val end = window?.until?.takeIf { window.basis == COMPLETED_REQUEST_WINDOW && window.reason == null }
        val fresh = when (measurement.reason) {
            null -> now
            COMPLETED_UNCHANGED -> state.lastFreshEvaluation
            else -> null
        }
        return state.copy(lastEvaluation = now, lastWindowEnd = end ?: state.lastWindowEnd, lastFreshEvaluation = fresh)
    }

    private fun active(prior: AlertState, rule: AlertRule, value: Double, now: Long, elapsed: Long): AlertState {
        if (!rule.recovered(
                value,
            )
        ) {
            return prior.copy(state = "firing", recoverySince = null, recoveredMs = 0, reason = null)
        }
        val since = prior.recoverySince ?: now
        val held = held(prior.recoverySince, prior.recoveredMs, elapsed, rule.recovery.holdMs)
        return if (held >= rule.recovery.holdMs) {
            prior.copy(
                state = "resolved",
                active = false,
                resolvedAt = now,
                recoverySince = null,
                recoveredMs = held,
                reason = "recovered",
            )
        } else {
            prior.copy(state = "firing", recoverySince = since, recoveredMs = held, reason = "recovery_pending")
        }
    }

    private fun pending(prior: AlertState, rule: AlertRule, now: Long, elapsed: Long): AlertState {
        val since = prior.pendingSince ?: now
        val held = held(prior.pendingSince, prior.heldMs, elapsed, rule.holdMs)
        val cooling = prior.resolvedAt?.let { now - it < rule.cooldownMs } == true
        return if (held >= rule.holdMs && !cooling) {
            prior.copy(
                state = "firing",
                active = true,
                alertId = ObjectiveJson.hash("${prior.revision}:$since"),
                pendingSince = since,
                heldMs = held,
                firingSince = now,
                resolvedAt = null,
                reason = null,
            )
        } else {
            prior.copy(
                state = "pending",
                pendingSince = since,
                heldMs = held,
                reason = if (cooling) "cooldown" else "hold",
            )
        }
    }

    private fun held(since: Long?, previous: Long, elapsed: Long, required: Long): Long =
        if (since == null) 0 else minOf(required, previous + minOf(elapsed, required))
}
