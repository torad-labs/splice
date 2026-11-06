package splice.usage.perf.objectives

import splice.usage.perf.query.EvidencePoints
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService

internal data class AlertRuleWindow(
    val from: Long?,
    val until: Long?,
    val basis: String,
    val horizon: Long? = null,
    val maxLagMs: Long? = null,
    val reason: String? = null,
)

/** Alert-only policy. Public queries retain their caller's exact bounds and denominator. */
internal class AlertRuleWindows(private val query: PerfQueryService) {
    private val requestMetrics = EvidencePoints(PerfQuery(0, 1)).supported

    fun select(rule: AlertRule, context: AlertContext, previous: AlertState, now: Long): AlertRuleWindow {
        if (!requestBased(rule.metric)) {
            val basis = if (rule.metric == "telemetry.staleness_ms") {
                "current_sink_snapshot"
            } else {
                "current_aggregate_window"
            }
            return window(now, rule.windowMs, basis)
        }
        val horizon = query.settledUntilMs(context.cohort["head"])
        val maxLag = maxOf(MIN_COMPLETED_LAG_MS, minOf(rule.windowMs, MAX_COMPLETED_LAG_MS))
        return window(horizon, rule.windowMs, COMPLETED_REQUEST_WINDOW).copy(
            horizon = horizon,
            maxLagMs = maxLag,
            reason = problem(horizon, rule, previous, now),
        )
    }

    private fun problem(horizon: Long?, rule: AlertRule, previous: AlertState, now: Long): String? = when {
        horizon == null -> "completed_window_unavailable"
        horizon > now -> "completed_window_in_future"
        horizon < rule.windowMs -> "window_before_epoch"
        now - horizon > maxOf(
            MIN_COMPLETED_LAG_MS,
            minOf(rule.windowMs, MAX_COMPLETED_LAG_MS),
        ) -> "completed_window_stale"
        previous.lastWindowEnd?.let { horizon < it } == true -> "completed_window_regressed"
        previous.lastWindowEnd == horizon -> COMPLETED_UNCHANGED
        else -> null
    }

    private fun requestBased(metric: String): Boolean = metric in requestAliases ||
        (
            metric.substringBefore(
                '.',
            ) in setOf("histograms", "counters") && metric.split('.').getOrNull(1) in requestMetrics
            )

    private fun window(until: Long?, duration: Long, basis: String): AlertRuleWindow = AlertRuleWindow(
        from = until?.takeIf { it >= duration }?.minus(duration),
        until = until,
        basis = basis,
        reason = "window_before_epoch".takeIf { until != null && until < duration },
    )
}

internal const val COMPLETED_REQUEST_WINDOW = "certified_completed_request_window"
internal const val COMPLETED_UNCHANGED = "completed_window_unchanged"

// Reject completed-window evidence older than one minute even when a rule uses a longer measurement window.
private const val MAX_COMPLETED_LAG_MS = 60_000L

// Allow fifteen seconds of checkpoint settling lag for short windows before classifying completed evidence as stale.
private const val MIN_COMPLETED_LAG_MS = 15_000L
private val requestAliases = setOf(
    "goodput.good_fraction",
    "goodput.per_s",
    "failure_fraction",
    "refusal_fraction",
    "retry_amplification",
)
