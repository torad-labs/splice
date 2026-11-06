package splice.core.perf.observation

import kotlinx.serialization.Serializable

@Serializable
public data class PerfIssue(
    val code: String,
    val source: String,
    val count: Long = 1,
    val detail: String? = null,
)

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
)

@Serializable
public data class PerfCoverage(
    val requestedFromMs: Long,
    val requestedUntilMs: Long,
    val observedMs: Long,
    val issues: List<PerfIssue> = emptyList(),
    val sinks: List<PerfSinkStatus> = emptyList(),
)
