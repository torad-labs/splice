package splice.core.perf.observation

import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Synchronous, bounded observation creation. The observer owns persistence acknowledgement and loss accounting. */
public class PerfRecorder(
    identity: PerfIdentity,
    private val observer: PerfObserver,
    private val clock: ElapsedClock,
    private val wallClock: WallClock,
) {
    private val identity = PerfProvenance.bounded(identity)
    private val sequence = AtomicLong()
    private val recorderId = UUID.randomUUID().toString()

    public fun emit(
        scope: PerfScope,
        kind: PerfEventKind,
        values: Map<String, Double> = emptyMap(),
        links: PerfLinks = PerfLinks(),
        outcome: String? = null,
    ): Boolean = if (observer === NoPerfObserver) {
        true
    } else {
        publish(PerfObservation(stamp(), scope, kind, values, links, outcome))
    }

    public fun request(scope: PerfScope, arrivalMonotonicMs: Long, arrivalEpochMs: Long): PerfRequestRecorder {
        val arrival = PerfStamp(nextId(), identity, arrivalEpochMs, arrivalMonotonicMs)
        val links = PerfLinks(requestId = arrival.observationId)
        val request = PerfRequestRecorder(this, PerfDimensions.scope(scope), links, arrivalMonotonicMs)
        publish(PerfObservation(arrival, scope, PerfEventKind.REQUEST_ARRIVED, links = links))
        return request
    }

    internal fun elapsedSince(origin: Long): Long = (clock() - origin).coerceAtLeast(0)

    internal fun stamp(): PerfStamp = PerfStamp(nextId(), identity, wallClock(), clock())

    internal fun attributedStamp(origin: PerfStamp): PerfStamp = origin.copy(observationId = nextId())

    internal fun attempt(scope: PerfScope, parent: PerfLinks, transport: String): PerfAttemptRecorder {
        val start = stamp()
        val attemptScope = PerfDimensions.scope(scope.copy(transport = transport, work = "upstream", outcome = null))
        val links = PerfDimensions.links(
            parent.copy(
                attemptId = start.observationId,
                parentSpanId = parent.spanId,
                spanId = UUID.randomUUID().toString().replace("-", "").take(SPAN_HEX_LENGTH),
            ),
        )
        publish(PerfObservation(start, attemptScope, PerfEventKind.ATTEMPT_STARTED, links = links))
        return PerfAttemptRecorder(this, attemptScope, links, start.monotonicMs)
    }

    internal fun tool(scope: PerfScope, parent: PerfLinks, traceLinks: PerfLinks?): PerfToolRecorder {
        val start = stamp()
        val toolScope = PerfDimensions.scope(scope.copy(work = "tool", outcome = null))
        val native = traceLinks?.let(PerfDimensions::links)?.takeIf { it.traceId != null && it.spanId != null }
        val links = PerfDimensions.links(
            parent.copy(
                toolId = start.observationId,
                traceId = native?.traceId ?: parent.traceId,
                traceFlags = native?.traceFlags ?: parent.traceFlags,
                parentSpanId = native?.parentSpanId ?: parent.spanId,
                spanId = native?.spanId ?: UUID.randomUUID().toString().replace("-", "").take(SPAN_HEX_LENGTH),
            ),
        )
        val values = if (native != null) mapOf("tool_native_span" to 1.0) else emptyMap()
        publish(PerfObservation(start, toolScope, PerfEventKind.TOOL_STARTED, values, links))
        return PerfToolRecorder(this, toolScope, links, start.monotonicMs)
    }

    private fun nextId(): String =
        "${identity.bootId.take(PerfObservationLimits.MAX_LABEL / 2)}:$recorderId:${sequence.incrementAndGet()}"

    internal fun publish(observation: PerfObservation): Boolean {
        if (observer === NoPerfObserver) return true
        val values = observation.values.filter { (key, value) ->
            PerfMetricRegistry.accepts(key, observation.kind, value)
        }
        val safe = observation.copy(
            values = values,
            scope = PerfDimensions.scope(observation.scope),
            links = PerfDimensions.links(observation.links),
            outcome = observation.outcome?.let(PerfDimensions::terminalOutcome),
        )
        val accepted = observer.record(safe)
        val dropped = observation.values.size - values.size
        if (dropped > 0) {
            observer.record(
                PerfObservation(
                    stamp(),
                    safe.scope,
                    PerfEventKind.SELF_COST,
                    mapOf("self_dropped" to dropped.toDouble()),
                ),
            )
        }
        return accepted && dropped == 0
    }
}

private const val SPAN_HEX_LENGTH = 16
