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
)

internal object AlertLifecycle {
    fun advance(
        previous: AlertState,
        rule: AlertRule,
        measurement: AlertMeasurement,
        now: Long,
        restarted: Boolean,
    ): AlertState {
        val gap = previous.lastEvaluation?.let { now - it > rule.windowMs } == true
        val prior = if (restarted || gap) previous.copy(pendingSince = null, recoverySince = null) else previous
        val next = when {
            measurement.reason != null -> prior.copy(
                state = "insufficient_data",
                pendingSince = null,
                recoverySince = null,
                reason = measurement.reason,
            )
            prior.active -> active(prior, rule, requireNotNull(measurement.observed), now)
            rule.breached(requireNotNull(measurement.observed)) -> pending(prior, rule, now)
            else -> prior.copy(
                state = "resolved",
                pendingSince = null,
                recoverySince = null,
                reason = "healthy_measurement",
            )
        }
        return next.copy(lastEvaluation = now)
    }

    private fun active(prior: AlertState, rule: AlertRule, value: Double, now: Long): AlertState {
        if (!rule.recovered(value)) return prior.copy(state = "firing", recoverySince = null, reason = null)
        val since = prior.recoverySince ?: now
        return if (now - since >= rule.recovery.holdMs) {
            prior.copy(state = "resolved", active = false, resolvedAt = now, recoverySince = null, reason = "recovered")
        } else {
            prior.copy(state = "firing", recoverySince = since, reason = "recovery_pending")
        }
    }

    private fun pending(prior: AlertState, rule: AlertRule, now: Long): AlertState {
        val since = prior.pendingSince ?: now
        val cooling = prior.resolvedAt?.let { now - it < rule.cooldownMs } == true
        return if (now - since >= rule.holdMs && !cooling) {
            prior.copy(
                state = "firing",
                active = true,
                alertId = ObjectiveJson.hash("${prior.revision}:$since"),
                pendingSince = since,
                firingSince = now,
                resolvedAt = null,
                reason = null,
            )
        } else {
            prior.copy(state = "pending", pendingSince = since, reason = if (cooling) "cooldown" else "hold")
        }
    }
}
