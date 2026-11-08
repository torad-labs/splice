package splice.core.perf.observation

import kotlinx.serialization.Serializable

@Serializable
public data class PerfIssue(
    val code: String,
    val source: String,
    val count: Long = 1,
    val detail: String? = null,
    /** Optional inclusive start of the event-time interval affected by this issue. */
    val fromMs: Long? = null,
    /** Optional exclusive end of the event-time interval affected by this issue. */
    val untilMs: Long? = null,
) {
    /** Keeps legacy unbounded issues conservative while excluding bounded issues outside a query window. */
    public fun overlaps(windowFromMs: Long, windowUntilMs: Long): Boolean = when {
        fromMs == null || untilMs == null -> true
        untilMs <= fromMs -> true
        else -> fromMs < windowUntilMs && windowFromMs < untilMs
    }
}

@Serializable
public data class PerfSinkStatus(
    val sink: String,
    val observed: Long,
    val enqueued: Long,
    val written: Long,
    val failed: Long,
    val dropped: Long,
    val pending: Long,
    val oldestPendingMs: Long?,
    val lastSuccessEpochMs: Long?,
    val rejections: PerfSinkRejections? = null,
)

@Serializable
public data class PerfSinkRejections(
    val snapshotMemory: Long = 0,
    val serializationSize: Long = 0,
    val historyMemory: Long = 0,
    val phaseUnavailable: Long = 0,
    val writerUnavailable: Long = 0,
    val asyncEnqueue: Long = 0,
    val unclassified: Long = 0,
    val last: PerfSinkRejection? = null,
)

@Serializable
public data class PerfSinkRejection(
    val reason: String,
    val atEpochMs: Long,
    /** Bytes the refused budget operation attempted to add; transfer reports its marginal addition. */
    val requestedBytes: Long? = null,
    /** Total lease size targeted by the operation; equals requestedBytes for a direct acquire. */
    val targetBytes: Long? = null,
    val leasedBytes: Long? = null,
)

@Serializable
public data class PerfCoverage(
    val requestedFromMs: Long,
    val requestedUntilMs: Long,
    val observedMs: Long,
    val issues: List<PerfIssue> = emptyList(),
    val sinks: List<PerfSinkStatus> = emptyList(),
)
