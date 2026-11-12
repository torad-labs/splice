package splice.head.wire.observation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRequestRecorder
import splice.core.util.JsonScalars
import splice.head.wire.MESSAGE_STOP
import splice.head.wire.Utf8Length

internal data class DeliveredBody(
    val text: String,
    val bytes: Long,
    val elapsedMs: Long,
    val status: Int,
    val ending: String?,
)

/** Observes already-flushed output. Payloads are inspected locally; only numeric timings leave this seam. */
internal object DeliveryObservation {
    fun frame(request: PerfRequestRecorder?, frame: String, writeMs: Long, model: Boolean = true) {
        if (request == null) return
        progress(request, Utf8Length.of(frame), writeMs)
        if (model && frame.startsWith("event: content_block_")) {
            val data = frame.substringAfter("data: ", "").trim()
            objectOf(data)?.let { event ->
                val block = event["delta"] as? JsonObject ?: event["content_block"] as? JsonObject
                block?.let { content(request, it) }
            }
        }
        when {
            frame.startsWith("event: $MESSAGE_STOP\n") -> request.finish(OutcomeTag.OK.wire)
            frame.startsWith("event: error\n") -> request.finish(OutcomeTag.UPSTREAM_FAILED.wire)
        }
    }

    fun body(request: PerfRequestRecorder?, body: DeliveredBody) {
        if (request == null) return
        progress(request, body.bytes, body.elapsedMs)
        val json = objectOf(body.text)
        (json?.get("content") as? JsonArray)?.forEach { (it as? JsonObject)?.let { block -> content(request, block) } }
        val outcome = body.ending ?: if (body.status >= HttpStatusCode.BadRequest.value || JsonScalars.str(
                json?.get("type"),
            ) == "error"
        ) {
            OutcomeTag.UPSTREAM_FAILED.wire
        } else if (JsonScalars.str(json?.get("type")) == "message") {
            OutcomeTag.OK.wire
        } else {
            "?"
        }
        request.finish(outcome)
    }

    fun firstByte(request: PerfRequestRecorder?) {
        request?.observe(
            PerfEventKind.CLIENT_PROGRESS,
            mapOf("arrival_to_first_client_byte_ms" to request.elapsedMs().toDouble()),
        )
    }

    private fun progress(request: PerfRequestRecorder, bytes: Long, writeMs: Long) {
        if (bytes > 0) firstByte(request)
        request.observe(
            PerfEventKind.CLIENT_PROGRESS,
            mapOf(
                "downstream_bytes" to bytes.toDouble(),
                "downstream_events" to 1.0,
                "client_write_flush_ms" to writeMs.coerceAtLeast(0).toDouble(),
            ),
        )
    }

    private fun content(request: PerfRequestRecorder, block: JsonObject) {
        val field = when (JsonScalars.str(block["type"])) {
            "text", "text_delta" -> "text".takeIf { !JsonScalars.str(block["text"]).isNullOrEmpty() }
            "thinking", "thinking_delta" -> "reasoning".takeIf { !JsonScalars.str(block["thinking"]).isNullOrEmpty() }
            "tool_use", "server_tool_use" -> "tool"
            "input_json_delta" -> "tool".takeIf { !JsonScalars.str(block["partial_json"]).isNullOrEmpty() }
            else -> null
        } ?: return
        val elapsed = request.elapsedMs().toDouble()
        request.observe(
            PerfEventKind.CONTENT_PROGRESS,
            mapOf("arrival_to_first_meaningful_content_ms" to elapsed, "arrival_to_first_${field}_ms" to elapsed),
        )
    }

    private fun objectOf(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
