package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue

internal class QueryData(query: PerfQuery) {
    val points = LinkedHashMap<PointKey, PerfAggregatePoint>()
    val identities = LinkedHashSet<PerfIdentity>()
    val missing = LinkedHashSet<String>()
    var coverage = PerfCoverage(query.fromMs, query.untilMs, 0)
    var asOf = query.untilMs
    var settledUntilMs: Long? = null
    var collectionMode: String? = null
    var resolution = 0L
    var complete = false
    var truncated = false
    var counts = JsonObject(emptyMap())
    var goodput = JsonObject(emptyMap())
    var legacy = JsonObject(emptyMap())
    var configuration = JsonObject(emptyMap())
    var workload = PerfQueryJson.unavailableWorkload()
    var timeline = JsonObject(emptyMap())
    var evidenceAvailable = false
    val populationCoverage = LinkedHashMap<String, JsonObject>()
    var populationSource = "aggregate_event_time_scope"

    fun issue(code: String, source: String) {
        if (coverage.issues.none { it.code == code && it.source == source }) {
            coverage = coverage.copy(issues = coverage.issues + PerfIssue(code, source))
        }
    }

    fun status(): String = when {
        coverage.issues.any { it.code.contains("busy") } && points.isEmpty() -> "busy"
        coverage.issues.isNotEmpty() || !complete || truncated -> "partial"
        points.isEmpty() -> "empty"
        else -> OutcomeTag.OK.wire
    }
}

internal data class PointKey(
    val identity: PerfIdentity,
    val metric: String,
    val scope: splice.core.perf.observation.PerfScope,
    val fromMs: Long,
)
