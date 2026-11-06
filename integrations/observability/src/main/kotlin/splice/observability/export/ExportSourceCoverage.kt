package splice.observability.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue

/** Only finite source/code names cross the status boundary; free-form issue details never do. */
internal object ExportSourceCoverage {
    fun of(source: PerfCoverage): JsonObject {
        val issues = source.issues.take(MAX_ISSUES)
        val groups = issues.groupBy {
            (it.code.takeIf(codes::contains) ?: UNKNOWN) to
                (it.source.takeIf { name -> name == "accumulator" } ?: UNKNOWN)
        }
        return buildJsonObject {
            put("requested_from_ms", source.requestedFromMs)
            put("requested_until_ms", source.requestedUntilMs)
            put("observed_ms", source.observedMs)
            put("partial", source.observedMs < source.requestedUntilMs - source.requestedFromMs)
            put("issue_entries_omitted", source.issues.size - issues.size)
            put("issue_entries_normalized", issues.count { it.code !in codes || it.source != "accumulator" })
            put(
                "issues",
                JsonArray(
                    groups.map { (key, values) ->
                        buildJsonObject {
                            put("code", key.first)
                            put("source", key.second)
                            put("count", count(values))
                        }
                    },
                ),
            )
        }
    }

    fun count(issues: List<PerfIssue>): Long = issues.fold(0L) { total, issue ->
        total + issue.count.coerceIn(0, Long.MAX_VALUE - total)
    }

    // PerfAccumulator is the live export source; new codes remain explicit as unknown until approved.
    private val codes = setOf(
        "invalid_observation", "identity_mismatch", "aggregate_overflow", "cardinality_overflow",
        "gauge_overflow", "active_handle_overflow", "terminal_without_start", "clock_discontinuity",
        "late_observation", "late_observation_expired", "memory_budget_exhausted", "observation_dropped",
        "retention_expired", "no_observations", "collector_closed", "snapshot_busy",
    )
}

private const val MAX_ISSUES = 64
private const val UNKNOWN = "unknown"
