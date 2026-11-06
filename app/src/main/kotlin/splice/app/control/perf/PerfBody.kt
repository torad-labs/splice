package splice.app.control.perf

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import splice.core.util.PerfWorkBudget

/** Bounded before text decoding and JSON parsing, under the already authenticated route admission. */
internal object PerfBody {
    suspend fun consume(call: ApplicationCall, work: PerfHttpWork, block: PerfBodyRead) {
        val lease = PerfWorkBudget.acquire(BODY_WORK_BYTES)
        if (lease == null) {
            work.refusal(call, "busy", "request_memory_budget", HttpStatusCode.TooManyRequests)
            return
        }
        lease.use {
            val document = read(call)
            if (document == null) {
                work.refusal(call, "error", "invalid_or_oversized_json_body", HttpStatusCode.BadRequest)
            } else {
                block.consume(document)
            }
        }
    }

    private suspend fun read(call: ApplicationCall): JsonObject? {
        val bytes = ByteArray(MAX_BODY_BYTES + 1)
        val channel = call.receiveChannel()
        var size = 0
        var ended = false
        while (size < bytes.size && !ended) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(bytes, size, bytes.size - size)
            when {
                read < 0 -> ended = true
                read == 0 -> ended = !channel.awaitContent(1)
                else -> size += read
            }
        }
        return if (size <= MAX_BODY_BYTES) parse(bytes, size) else null
    }

    private fun parse(bytes: ByteArray, size: Int): JsonObject? = try {
        val text = bytes.decodeToString(0, size, throwOnInvalidSequence = true)
        JsonWire.requireRequestNesting(text)
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun interface PerfBodyRead {
    suspend fun consume(document: JsonObject)
}

// Reject control documents above 64 KiB before decoding so settings input cannot allocate an unbounded JSON tree.
private const val MAX_BODY_BYTES = 64 * 1_024

// Reserve 512 KiB for the bounded body, decoded text and JSON tree until the route finishes consuming it.
private const val BODY_WORK_BYTES = 512L * 1_024
