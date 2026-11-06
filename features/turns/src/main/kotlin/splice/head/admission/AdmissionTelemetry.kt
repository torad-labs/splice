// NEW: the admission plane's perf stamps (HD-24) — the row a turn opens with and the three counts
// it earns the moment a gate slot is in hand. Its own file because this is the only place the
// admission path touches splice.core.perf and splice.core.util, and the ORDER is the contract:
// the row is opened before the admission clock reading, and the stamps land immediately after the
// slot is returned, so INFLIGHT is the depth the turn actually entered at.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRequestRecorder
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.upstream.retry.InflightGate
import java.util.UUID

internal class AdmissionTelemetry(
    private val gate: InflightGate,
    private val clock: ElapsedClock,
) {
    fun arrivalTime(): Long = clock()

    fun begin(arrivalAt: Long = clock(), observations: PerfRequestRecorder? = null): TurnPerf =
        TurnPerf(observations = observations, clock = clock).also {
            it.recordArrival(arrivalAt)
            it.setCount(PerfKeys.PRE_ADMISSION_MS, it.arrivalElapsedMs())
        }

    suspend fun <T> prepare(perf: TurnPerf, leaseStart: Long, work: TimedWork<T>): T {
        val started = clock()
        perf.setCount(PerfKeys.LEASE_WAIT_MS, started - leaseStart)
        try {
            return work()
        } finally {
            perf.setCount(PerfKeys.PREP_MS, clock() - started)
        }
    }

    /** [heldMs] is the gate's own answer: how long it held this request behind a full limit, or null when it went
     *  straight in. Only a held request carries ADMIT_HELD_MS, the field "Waited in line" counts. */
    fun markAdmitted(perf: TurnPerf, waitingSince: Long = clock(), heldMs: Long? = null) {
        perf.setCount(PerfKeys.ADMIT_WAIT_MS, clock() - waitingSince)
        if (heldMs != null) perf.setCount(PerfKeys.ADMIT_HELD_MS, heldMs)
        perf.mark(PerfKeys.GATE)
        perf.setCount(PerfKeys.INFLIGHT, gate.snapshot().inflight.toLong())
        perf.setCount(PerfKeys.ASYNC_IO_DROPS, AsyncFileIo.droppedCount().toLong())
        perf.observations?.observe(
            PerfEventKind.REQUEST_ADMITTED,
            mapOf("queue_wait_ms" to (heldMs ?: 0L).toDouble()),
        )
    }
}

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
