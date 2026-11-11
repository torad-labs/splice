package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus

/** Coverage is folded separately from display buckets, so coarsening never fills time gaps. */
internal class PerfReadCoverage(private val from: Long, private val until: Long, evidence: List<PerfIssue>) {
    private val issues = LinkedHashMap<PerfReadIssueKey, PerfIssue>()
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
        snapshot.coverage.issues.forEach(::observe)
    }

    /** A stored bucket that crosses an edge of the window stays out of the read: its time is not counted as
     *  observed, and the result names the edge. */
    fun excluded(snapshot: PerfAggregateSnapshot) {
        edge = true
        snapshot.coverage.issues.forEach(::observe)
    }

    fun error(code: String, times: Long = 1L) {
        val key = PerfReadIssueKey(code, HISTORY_SINK, null, null, null)
        val count = (issues[key]?.count ?: 0L) + times
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
        if (!value.overlaps(from, until)) return
        val key = PerfReadIssueKey(value.code, value.source, value.detail, value.fromMs, value.untilMs)
        val existing = issues[key]
        val sameCode = issues.entries.filter { it.value.code == value.code && it.value.source == value.source }
        val room = issues.size < MAX_READ_COVERAGE_ISSUES &&
            issueBytes + PerfHistorySize.issue(value) <= READ_COVERAGE_ISSUE_BUDGET
        when {
            existing != null -> issues[key] = existing.copy(count = maxOf(existing.count, value.count))
            sameCode.size >= MAX_ISSUES_PER_CODE -> widen(sameCode.last().key, value)
            room -> {
                issues[key] = value
                issueBytes += PerfHistorySize.issue(value)
            }
            else -> issues[PerfReadIssueKey("coverage_issue_limit", HISTORY_SINK, null, null, null)] =
                PerfIssue("coverage_issue_limit", HISTORY_SINK)
        }
    }

    /** One code's later intervals widen its last kept entry to span them, so a code that repeats cannot crowd the
     *  others out. On Nov 11, 2026 a day's read carried 55 "retention_expired" intervals, the limit left no room
     *  for "query_memory_limit", and the read's gap was reported without its cause. */
    private fun widen(key: PerfReadIssueKey, value: PerfIssue) {
        val kept = issues.remove(key) ?: return
        val from = kept.fromMs?.let { a -> value.fromMs?.let { b -> minOf(a, b) } }
        val until = kept.untilMs?.let { a -> value.untilMs?.let { b -> maxOf(a, b) } }
        // An entry without both bounds stays unbounded: it then counts against every window, the cautious reading.
        val bounded = from != null && until != null
        val spanned = kept.copy(
            count = maxOf(kept.count, value.count),
            detail = null,
            fromMs = from.takeIf { bounded },
            untilMs = until.takeIf { bounded },
        )
        issueBytes += PerfHistorySize.issue(spanned) - PerfHistorySize.issue(kept)
        val wide = PerfReadIssueKey(spanned.code, spanned.source, null, spanned.fromMs, spanned.untilMs)
        issues[wide] = issues[wide]?.let { it.copy(count = maxOf(it.count, spanned.count)) } ?: spanned
    }
}

private data class PerfReadIssueKey(
    val code: String,
    val source: String,
    val detail: String?,
    val fromMs: Long?,
    val untilMs: Long?,
)

// Keep at most sixty-four distinct coverage issues while duplicate codes accumulate counts.
private const val MAX_READ_COVERAGE_ISSUES = 64

// Keep at most eight intervals of one code and source, so sixty-four entries always hold eight codes or more.
private const val MAX_ISSUES_PER_CODE = 8

// Limit retained issue details to 128 KiB independently of the issue-count bound.
private const val READ_COVERAGE_ISSUE_BUDGET = 128L * 1_024
