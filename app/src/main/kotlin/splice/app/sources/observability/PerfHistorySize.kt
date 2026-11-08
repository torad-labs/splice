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
        var bytes = HISTORY_SNAPSHOT_OVERHEAD + identity(value.identity) + coverage(value.coverage)
        if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        for (point in value.points) {
            bytes += point(point)
            if (bytes >= ESTIMATE_LIMIT) return ESTIMATE_LIMIT
        }
        return bytes
    }

    fun identity(value: PerfIdentity): Long = IDENTITY_BYTES +
        text(value.bootId) + text(value.build) + text(value.version) + text(value.configRevision)

    fun point(value: PerfAggregatePoint): Long = HISTORY_POINT_OVERHEAD + text(value.metric) + scope(value.scope) +
        value.buckets.size.toLong() * BUCKET_BYTES

    fun issue(value: PerfIssue): Long = HISTORY_ISSUE_OVERHEAD + text(
        value.code,
    ) + text(value.source) + text(value.detail)

    fun sink(value: PerfSinkStatus): Long = SINK_BYTES + text(value.sink)

    fun coverage(value: PerfCoverage): Long {
        var bytes = HISTORY_COVERAGE_OVERHEAD
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

    private fun scope(value: PerfScope): Long = HISTORY_SCOPE_OVERHEAD + text(value.head) + text(value.model) +
        text(value.transport) + text(value.work) + text(value.outcome)

    private fun text(value: String?): Long =
        if (value == null) 0L else HISTORY_STRING_OVERHEAD + value.length.toLong() * STRING_CHAR_BYTES
}

// Saturate ownership estimates at 16 MiB, the shared work ceiling, instead of accumulating beyond admissible work.
private const val ESTIMATE_LIMIT = 16L * 1_024 * 1_024

// Charge 512 bytes for snapshot collections and scalar fields before nested identity, coverage and points.
private const val HISTORY_SNAPSHOT_OVERHEAD = 512L

// Charge 256 bytes for the identity object in addition to each retained identifying string.
private const val IDENTITY_BYTES = 256L

// Charge 1 KiB for point scalar fields and object ownership before metric, scope and histogram charges.
private const val HISTORY_POINT_OVERHEAD = 1_024L

// Charge thirty-two bytes per histogram entry to cover boxed values and collection ownership.
private const val BUCKET_BYTES = 32L

// Charge 320 bytes per issue object before strings, including references and boxed optional time bounds.
private const val HISTORY_ISSUE_OVERHEAD = 320L

// Charge 256 bytes per sink-status object in addition to its retained name.
private const val SINK_BYTES = 256L

// Charge 256 bytes for coverage scalars and collection ownership before nested issues and sinks.
private const val HISTORY_COVERAGE_OVERHEAD = 256L

// Charge 256 bytes for scope scalars and object ownership before its retained dimension strings.
private const val HISTORY_SCOPE_OVERHEAD = 256L

// Charge sixty-four bytes for each String object before escaped content and transient encoding charges.
private const val HISTORY_STRING_OVERHEAD = 64L

// Six escaped JSON characters per UTF-16 code unit, including the transient encoded string.
private const val STRING_CHAR_BYTES = 12L
