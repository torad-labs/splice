// NEW: the admission plane's perf stamps (HD-24) — the row a turn opens with and the three counts
// it earns the moment a gate slot is in hand. Its own file because this is the only place the
// admission path touches splice.core.perf and splice.core.util, and the ORDER is the contract:
// the row is opened before the admission clock reading, and the stamps land immediately after the
// slot is returned, so INFLIGHT is the depth the turn actually entered at.
package splice.head.admission

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import splice.core.perf.LivenessProbe
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRequestRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.head.HeadDeps
import splice.head.turn.Preparation
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

    /** Installed before HeapIngress's Monitoring interceptor, including the calls it answers before routing. */
    fun install(application: Application, deps: HeadDeps, head: String) {
        application.intercept(ApplicationCallPipeline.Monitoring) {
            if (call.request.httpMethod == HttpMethod.Post && call.request.path() == "/v1/messages") {
                run(call, deps, head, deps.seams.clock()) { proceed() }
            } else {
                proceed()
            }
        }
    }

    suspend fun run(call: ApplicationCall, deps: HeadDeps, head: String, arrivalAt: Long, work: TimedWork<Unit>) {
        // Routing reuses the listener's handle. Direct handler callers still own one from this point. The daemon's own
        // liveness probe is no request (2026-11-09 audit): it posted every 30 s to every head and finished as "?",
        // two thirds of perf-evidence's finished rows, so a refused or unnamed real request read like one. Its header
        // alone refuses dispatch (ClientAuth), so leaving it out hides no work, and V4-454 already keeps it off the
        // head's perf file.
        if (of(call) != null || call.request.headers.getAll(LivenessProbe.PROBE_HEADER_NAME) != null) {
            work()
            return
        }
        val observation = deps.stores.observations.request(
            PerfScope(head = head),
            arrivalAt,
            deps.stores.perfStats.clock(),
        )
        attach(call, observation)
        var ending = OutcomeTag.UNEXPECTED.wire
        try {
            work()
            val refused = (call.response.status()?.value ?: 0) >= HttpStatusCode.BadRequest.value
            ending = if (refused) OutcomeTag.REFUSED.wire else "?"
        } catch (cancelled: CancellationException) {
            ending = OutcomeTag.CANCELLED.wire
            throw cancelled
        } finally {
            // Actual delivery owns successful closure. Every earlier return still closes its arrival.
            observation.finish(ending)
        }
    }

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

    suspend fun acquire(
        call: ApplicationCall,
        gate: InflightGate,
        session: String?,
        clock: ElapsedClock,
    ): InflightGate.Admission {
        val started = clock()
        try {
            return gate.acquire(session)
        } catch (cancelled: CancellationException) {
            of(call)?.finish(
                OutcomeTag.CANCELLED.wire,
                mapOf("queue_wait_ms" to (clock() - started).coerceAtLeast(0).toDouble()),
            )
            throw cancelled
        }
    }

    fun ready(perf: TurnPerf, prepared: Preparation.Ready) {
        val meta = prepared.built.meta
        perf.observations?.let {
            it.update(
                it.scope.copy(
                    model = meta.route.upstreamModel,
                    streaming = prepared.stream,
                    compact = meta.compact,
                    local = false,
                ),
                it.links.copy(sessionId = meta.scope.sessionId),
            )
        }
    }

    /** A request refused while it was prepared names the model, session and transport it asked for, as a served one
     *  does, so its row is not a bare `{head}` beside the probes. [model] is empty when its body did not parse. */
    fun refused(perf: TurnPerf, model: String, session: String?, stream: Boolean) {
        perf.observations?.let {
            it.update(
                it.scope.copy(model = model.ifEmpty { null }, streaming = stream, local = false),
                it.links.copy(sessionId = session),
            )
        }
    }

    fun turn(perf: TurnPerf, turnId: String?) {
        perf.observations?.let { it.update(it.scope, it.links.copy(turnId = turnId)) }
    }

    fun local(perf: TurnPerf, model: String, session: String?, stream: Boolean) {
        perf.observations?.let {
            it.update(
                it.scope.copy(model = model, local = true, streaming = stream),
                it.links.copy(sessionId = session),
            )
            // These prepared branches serve an answer without a new provider request. Unknown arrivals and
            // code-mode Ready turns must leave canonical usage to their own settlement owner.
            it.usage(mapOf("missing_usage" to 0.0))
        }
    }
}

// A W3C span identifier contains sixteen hexadecimal characters, drawn here from a generated UUID.
private const val SPAN_ID_CHARS = 16

// The traceparent regex captures flags after the trace and parent span identifiers as its third group.
private const val TRACE_FLAGS_GROUP = 3
