package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus

/** Coverage is folded separately from display buckets, so coarsening never fills time gaps. */
internal class PerfReadCoverage(private val from: Long, private val until: Long, evidence: List<PerfIssue>) {
    private val issues = LinkedHashMap<Triple<String, String, String?>, PerfIssue>()
    private var issueBytes = 0L
    private var end = from
    private var covered = 0L
    private var edge = false

    init { evidence.forEach(::observe) }

    fun add(snapshot: PerfAggregateSnapshot) {
        val start = maxOf(from, end, snapshot.fromMs)
        val stop = minOf(until, snapshot.untilMs, snapshot.fromMs + snapshot.coverage.observedMs)
        if (stop > start) covered += stop - start
        end = maxOf(end, stop)
        edge = edge || snapshot.fromMs < from || snapshot.untilMs > until
        snapshot.coverage.issues.forEach(::observe)
    }

    fun error(code: String) {
        val key = Triple(code, HISTORY_SINK, null)
        val count = (issues[key]?.count ?: 0L) + 1
        observe(PerfIssue(code, HISTORY_SINK, count))
    }

    fun result(sink: PerfSinkStatus): PerfCoverage {
        if (covered < until - from) {
            observe(PerfIssue("coverage_gap", HISTORY_SINK, detail = "unobserved_ms=${until - from - covered}"))
        }
        if (edge) error("partial_boundary_bucket")
        return PerfCoverage(from, until, covered, issues.values.toList(), listOf(sink))
    }

    private fun observe(value: PerfIssue) {
        val key = Triple(value.code, value.source, value.detail)
        val existing = issues[key]
        if (existing != null) {
            issues[key] = existing.copy(count = maxOf(existing.count, value.count))
        } else if (issues.size < MAX_ISSUES && issueBytes + PerfHistorySize.issue(value) <= ISSUE_BYTES) {
            issues[key] = value
            issueBytes += PerfHistorySize.issue(value)
        } else {
            issues[Triple("coverage_issue_limit", HISTORY_SINK, null)] = PerfIssue("coverage_issue_limit", HISTORY_SINK)
        }
    }
}

private const val MAX_ISSUES = 64
private const val ISSUE_BYTES = 128L * 1_024
