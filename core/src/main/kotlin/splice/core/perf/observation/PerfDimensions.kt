package splice.core.perf.observation

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags

/** Keep free-form text out of dimensions; cap the complete dimension product in the accumulator. */
internal object PerfDimensions {
    private val outcomes = setOf("good", "bad", UNKNOWN_DIMENSION, "cancelled", "refused")
    private val transports = setOf("sse", "ws", "websocket", "http", "local", "mcp", UNKNOWN_DIMENSION)
    private val workKinds = setOf(
        "client", "compact", "tool", "compare", "local", "runtime", "instrumentation", "upstream", UNKNOWN_DIMENSION,
    )

    fun scope(scope: PerfScope): PerfScope = scope.copy(
        head = scope.head.takeIf(PerfDimensionText::label) ?: UNKNOWN_DIMENSION,
        model = scope.model?.takeIf(PerfDimensionText::label),
        transport = scope.transport?.takeIf { it in transports } ?: scope.transport?.let { UNKNOWN_DIMENSION },
        work = scope.work.takeIf { it in workKinds } ?: UNKNOWN_DIMENSION,
        outcome = scope.outcome?.let(::outcome),
    )

    fun links(links: PerfLinks): PerfLinks = links.copy(
        requestId = id(links.requestId), attemptId = id(links.attemptId), toolId = id(links.toolId),
        sessionId = id(links.sessionId), projectId = id(links.projectId), accountId = id(links.accountId),
        traceId = links.traceId?.takeIf(PerfDimensionText::trace),
        spanId = links.spanId?.takeIf(PerfDimensionText::span),
        parentSpanId = links.parentSpanId?.takeIf(PerfDimensionText::span),
        traceFlags = links.traceFlags?.takeIf(PerfDimensionText::flags),
        // Vendor trace-state may contain account data; export requires a separate explicit allowlist.
        traceState = null,
        turnId = id(links.turnId),
    )

    fun outcome(value: String): String = when {
        value in outcomes -> value
        OutcomeTags.isClean(value) -> "good"
        value == OutcomeTag.CANCELLED.wire || OutcomeTags.isStopped(value) -> "cancelled"
        value == OutcomeTag.AT_CAPACITY.wire -> "refused"
        value == "?" -> UNKNOWN_DIMENSION
        else -> "bad"
    }

    fun id(value: String?): String? = value?.takeIf(PerfDimensionText::identifier)

    fun terminalOutcome(value: String): String = when (value) {
        "good" -> OutcomeTag.OK.wire
        "bad" -> OutcomeTag.UNEXPECTED.wire
        "cancelled" -> OutcomeTag.CANCELLED.wire
        "refused", UNKNOWN_DIMENSION, "?" -> "?"
        else -> knownOutcome(value)
    }

    private fun knownOutcome(value: String): String = OutcomeTag.entries.firstOrNull { it.wire == value }?.wire
        ?: if (OutcomeTags.isStopped(value)) OutcomeTag.CLIENT_ABORT.wire else OutcomeTag.UNEXPECTED.wire
}

private const val UNKNOWN_DIMENSION = "unknown"
