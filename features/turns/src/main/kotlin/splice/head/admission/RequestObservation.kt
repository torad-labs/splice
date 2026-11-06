package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import splice.core.perf.observation.PerfRequestRecorder
import java.util.UUID

/** The arrival handle exists before a legacy perf row, including stopped and refused requests. */
internal object RequestObservation {
    private val key = AttributeKey<PerfRequestRecorder>("splice.request.observation")
    private val traceParent = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")

    fun attach(call: ApplicationCall, request: PerfRequestRecorder) {
        call.attributes.put(key, request)
        // W3C version 00 only. The recorder validates every ID and drops unsupported trace state.
        val parent = call.request.headers["traceparent"]?.let(traceParent::matchEntire)?.groupValues
            ?.takeIf { it[1].any { char -> char != '0' } && it[2].any { char -> char != '0' } }
        request.update(
            request.scope,
            request.links.copy(
                traceId = parent?.get(1) ?: UUID.randomUUID().toString().replace("-", ""),
                spanId = UUID.randomUUID().toString().replace("-", "").take(SPAN_ID_CHARS),
                parentSpanId = parent?.get(2),
                traceFlags = parent?.get(TRACE_FLAGS_GROUP),
            ),
        )
    }

    fun of(call: ApplicationCall): PerfRequestRecorder? = call.attributes.getOrNull(key)
}

// A W3C span identifier contains sixteen hexadecimal characters, drawn here from a generated UUID.
private const val SPAN_ID_CHARS = 16

// The traceparent regex captures flags after the trace and parent span identifiers as its third group.
private const val TRACE_FLAGS_GROUP = 3
