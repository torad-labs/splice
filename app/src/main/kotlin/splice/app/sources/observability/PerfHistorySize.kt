package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfSinkStatus

/** Conservative owned-object/escaped-JSON charges, bounded without rendering or copying caller values. */
internal object PerfHistorySize {
    fun snapshot(value: PerfAggregateSnapshot): Long {
        var bytes = SNAPSHOT_BYTES + identity(value.identity) + coverage(value.coverage)
        if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        for (point in value.points) {
            bytes += point(point)
            if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        }
        return bytes
    }

    fun identity(value: PerfIdentity): Long = IDENTITY_BYTES +
        text(value.bootId) + text(value.build) + text(value.version) + text(value.configRevision)

    fun point(value: PerfAggregatePoint): Long = POINT_BYTES + text(value.metric) + scope(value.scope) +
        value.buckets.size.toLong() * BUCKET_BYTES

    fun issue(value: PerfIssue): Long = ISSUE_BYTES + text(value.code) + text(value.source) + text(value.detail)

    fun sink(value: PerfSinkStatus): Long = SINK_BYTES + text(value.sink)

    fun coverage(value: PerfCoverage): Long {
        var bytes = COVERAGE_BYTES
        for (issue in value.issues) {
            bytes += issue(issue)
            if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        }
        for (sink in value.sinks) {
            bytes += sink(sink)
            if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        }
        return bytes
    }

    private fun scope(value: PerfScope): Long = SCOPE_BYTES + text(value.head) + text(value.model) +
        text(value.transport) + text(value.work) + text(value.outcome)

    private fun text(value: String?): Long =
        if (value == null) 0L else STRING_BYTES + value.length.toLong() * STRING_CHAR_BYTES
}

private const val ESTIMATE_LIMIT = 16L * 1_024 * 1_024
private const val SNAPSHOT_BYTES = 512L
private const val IDENTITY_BYTES = 256L
private const val POINT_BYTES = 1_024L
private const val BUCKET_BYTES = 32L
private const val ISSUE_BYTES = 256L
private const val SINK_BYTES = 256L
private const val COVERAGE_BYTES = 256L
private const val SCOPE_BYTES = 256L
private const val STRING_BYTES = 64L

// Six escaped JSON characters per UTF-16 code unit, including the transient encoded string.
private const val STRING_CHAR_BYTES = 12L
