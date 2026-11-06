package splice.core.perf.observation

import kotlinx.serialization.Serializable

/** Bounded metric dimensions. Correlation identifiers belong exclusively in [PerfLinks]. */
@Serializable
public data class PerfScope(
    val head: String = "unknown",
    val model: String? = null,
    val transport: String? = null,
    val work: String = "client",
    val streaming: Boolean? = null,
    val local: Boolean? = null,
    val compact: Boolean? = null,
    val outcome: String? = null,
)

@Serializable
public data class PerfLinks(
    val requestId: String? = null,
    val attemptId: String? = null,
    val toolId: String? = null,
    val traceId: String? = null,
    val sessionId: String? = null,
    val projectId: String? = null,
    val accountId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val traceFlags: String? = null,
    val traceState: String? = null,
)
