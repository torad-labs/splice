package splice.app.control.perf

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.observability.PerfTelemetry
import splice.core.util.JsonWire
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.PerfWorkPhase
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException

/** Two active reads and two suspended waiters across performance endpoints, all within one deadline. */
internal class PerfHttpWork(val io: CoroutineDispatcher = ProcessDispatchers().io()) {
    private val admitted = Semaphore(ACTIVE_REQUESTS + WAITING_REQUESTS)
    private val running = Semaphore(ACTIVE_REQUESTS)

    suspend fun execute(call: ApplicationCall, block: PerfControlWork) {
        withAdmission(call) { running.withPermit { block.run() } }
    }

    /** Phase waiting uses an admitted slot, never an idle active worker needed by status/settings reads. */
    suspend fun source(call: ApplicationCall, block: PerfControlWork) {
        withAdmission(call) { PerfWorkPhase.query().use { running.withPermit { block.run() } } }
    }

    private suspend fun withAdmission(call: ApplicationCall, block: PerfControlWork) {
        if (!admit()) {
            return refusal(
                call,
                "busy",
                "control_query_capacity",
                HttpStatusCode.TooManyRequests,
            )
        }
        try {
            val completed = withTimeoutOrNull(DEADLINE_MS) {
                block.run()
                true
            }
            if (completed != true) refusal(call, "busy", "control_query_deadline", HttpStatusCode.ServiceUnavailable)
        } catch (_: IOException) {
            refusal(call, "error", "performance_io_failure", HttpStatusCode.InternalServerError)
        } finally {
            admitted.release()
        }
    }

    /** A slot comes back a moment after its reply is written, and the console sends its next read as each reply
     *  lands. A read that finds every slot taken waits that moment out before it is refused, so it is never refused
     *  for a slot whose answer its client already holds. */
    private suspend fun admit(): Boolean =
        admitted.tryAcquire() || withTimeoutOrNull(ADMISSION_GRACE_MS) { admitted.acquire() } != null

    suspend fun respond(call: ApplicationCall, telemetry: PerfTelemetry?, read: PerfJsonRead) {
        val started = MonoClock.nowMs()
        val result = runInterruptible(io) { read.read() }
        telemetry?.measured("self_query_ms", MonoClock.nowMs() - started)
        try {
            val size = runInterruptible(io) { JsonWire.byteSize(result) }
            val lease = PerfWorkBudget.acquire(size + ENCODING_SCRATCH)
                ?: return refusal(call, "busy", "response_memory_budget", HttpStatusCode.TooManyRequests)
            lease.use {
                val encoding = MonoClock.nowMs()
                val bytes = runInterruptible(io) { JsonWire.bytes(result) }
                telemetry?.measured("self_serialize_ms", MonoClock.nowMs() - encoding)
                call.respondBytes(bytes, ContentType.Application.Json, PerfReplyStatus.of(result))
            }
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    suspend fun refusal(call: ApplicationCall, status: String, reason: String, code: HttpStatusCode) {
        call.respondText(error(status, reason).toString(), ContentType.Application.Json, code)
    }

    fun error(status: String, reason: String): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("status", status)
        put("reason", reason)
    }

    fun unavailable(): JsonObject = error("unsupported", "performance_unavailable")
}

internal fun interface PerfControlWork {
    suspend fun run()
}

internal fun interface PerfJsonRead {
    fun read(): JsonObject
}

// Keep active memory/IO work at two requests across all performance endpoints.
private const val ACTIVE_REQUESTS = 2

// Let the other two requests in a normal four-read console burst suspend without occupying threads.
private const val WAITING_REQUESTS = 2

// why: a slot whose reply is written is handed back within a few milliseconds even on a loaded host; a quarter of a
// second covers that many times over and is all a read that really is over capacity waits before its refusal.
private const val ADMISSION_GRACE_MS = 250L

// Include admission waiting in the existing fifteen-second deadline, so a queue cannot extend request lifetime.
private const val DEADLINE_MS = 15_000L

// Reserve 16 KiB beyond the measured response for JSON encoding and HTTP write bookkeeping.
private const val ENCODING_SCRATCH = 16L * 1_024
