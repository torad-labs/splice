package splice.observability.export

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp

internal object ExportFixtures {
    val identity = PerfIdentity("boot", "test", "1", "config")
    val scope = PerfScope(head = "test", model = "test-model", outcome = "good")
    val links = PerfLinks(traceId = "1234567890abcdef1234567890abcdef", spanId = "1234567890abcdef", traceFlags = "01")

    fun snapshot(count: Long = 3, from: Long = 0, until: Long = 60_000): PerfAggregateSnapshot = PerfAggregateSnapshot(
        identity,
        from,
        until,
        listOf(PerfAggregatePoint("requests_finished", scope, from, until, count, count.toDouble(), 1.0, 1.0)),
        PerfCoverage(from, until, until - from),
    )

    fun event(kind: PerfEventKind, at: Long = 1_000, context: PerfLinks = links): PerfObservation = PerfObservation(
        PerfStamp("${kind.name}-$at", identity, at, at),
        scope,
        kind,
        values = mapOf("arrival_to_terminal_ms" to 250.0, "prompt-secret" to 19.0),
        links = context.copy(requestId = "request-secret", sessionId = "session-secret"),
        outcome = "ok",
    )
}
