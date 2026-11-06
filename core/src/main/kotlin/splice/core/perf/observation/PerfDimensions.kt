package splice.core.perf.observation

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags

/** Keep free-form text out of dimensions; cap the complete dimension product in the accumulator. */
internal object PerfDimensions {
    private val label = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")
    private val trace = Regex("[0-9a-f]{32}")
    private val span = Regex("[0-9a-f]{16}")
    private val flags = Regex("[0-9a-f]{2}")
    private val transports = setOf("sse", "ws", "websocket", "http", "local", "mcp", UNKNOWN)
    private val workKinds =
        setOf("client", "compact", "tool", "compare", "local", "runtime", "instrumentation", "upstream", UNKNOWN)

    fun scope(scope: PerfScope): PerfScope = scope.copy(
        head = scope.head.takeIf(label::matches) ?: UNKNOWN,
        model = scope.model?.takeIf(label::matches),
        transport = scope.transport?.takeIf { it in transports } ?: scope.transport?.let { UNKNOWN },
        work = scope.work.takeIf { it in workKinds } ?: UNKNOWN,
        outcome = scope.outcome?.let(::outcome),
    )

    fun links(links: PerfLinks): PerfLinks = links.copy(
        requestId = id(links.requestId), attemptId = id(links.attemptId), toolId = id(links.toolId),
        sessionId = id(links.sessionId), projectId = id(links.projectId), accountId = id(links.accountId),
        traceId = links.traceId?.takeIf { trace.matches(it) && it.any { char -> char != '0' } },
        spanId = links.spanId?.takeIf { span.matches(it) && it.any { char -> char != '0' } },
        parentSpanId = links.parentSpanId?.takeIf { span.matches(it) && it.any { char -> char != '0' } },
        traceFlags = links.traceFlags?.takeIf(flags::matches),
        // Vendor trace-state may contain account data; export requires a separate explicit allowlist.
        traceState = null,
        turnId = id(links.turnId),
    )

    fun outcome(value: String): String = when {
        value in setOf("good", "bad", UNKNOWN, "cancelled", "refused") -> value
        OutcomeTags.isClean(value) -> "good"
        value == OutcomeTag.CANCELLED.wire || OutcomeTags.isStopped(value) -> "cancelled"
        value == OutcomeTag.AT_CAPACITY.wire -> "refused"
        value == "?" -> UNKNOWN
        else -> "bad"
    }

    fun id(value: String?): String? = value?.takeIf(identifier::matches)

    fun terminalOutcome(value: String): String = when (value) {
        "good" -> OutcomeTag.OK.wire
        "bad" -> OutcomeTag.UNEXPECTED.wire
        "cancelled" -> OutcomeTag.CANCELLED.wire
        "refused" -> OutcomeTag.AT_CAPACITY.wire
        UNKNOWN, "?" -> "?"
        else -> knownOutcome(value)
    }

    private fun knownOutcome(value: String): String = OutcomeTag.entries.firstOrNull { it.wire == value }?.wire
        ?: if (OutcomeTags.isStopped(value)) OutcomeTag.CLIENT_ABORT.wire else OutcomeTag.UNEXPECTED.wire
}

private const val UNKNOWN = "unknown"
