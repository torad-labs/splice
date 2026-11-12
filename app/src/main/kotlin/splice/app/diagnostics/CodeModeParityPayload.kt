package splice.app.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeTiming
import java.time.Duration

internal object CodeModeParityPayload {
    fun outcome(cellId: String, step: CodeModeStep): JsonObject = buildJsonObject {
        val kind = kind(step)
        put(
            kind,
            buildJsonObject {
                put("cell_id", cellId)
                put("content_items", JsonArray(content(step)))
                val timing = timing(step)
                timing?.hostDuration?.let { put("code_mode_host_duration_ns", nanos(it)) }
                if (kind == "Result") {
                    timing?.resultBufferedFor?.let { put("result_buffered_for_ns", nanos(it)) }
                    put(
                        "error_text",
                        (step as? CodeModeStep.Completed)?.error?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            },
        )
    }

    private fun timing(step: CodeModeStep): NativeCodeModeTiming? = when (step) {
        is CodeModeStep.Yielded -> step.timing
        is CodeModeStep.Completed -> step.timing
        is CodeModeStep.Calls, is CodeModeStep.Notified, is CodeModeStep.WaitingForResults -> null
    }

    private fun nanos(duration: Duration): String = duration.seconds.toBigInteger()
        .multiply(1_000_000_000L.toBigInteger()).add(duration.nano.toBigInteger()).toString()

    private fun kind(step: CodeModeStep): String = when (step) {
        is CodeModeStep.Yielded -> "Yielded"
        is CodeModeStep.Completed -> if (step.terminated) "Terminated" else "Result"
        is CodeModeStep.Calls, is CodeModeStep.Notified, is CodeModeStep.WaitingForResults ->
            error("Expected a native observation")
    }

    private fun content(step: CodeModeStep): List<JsonObject> = when (step) {
        is CodeModeStep.Yielded -> step.content
        is CodeModeStep.Completed -> step.content
        is CodeModeStep.Calls, is CodeModeStep.Notified, is CodeModeStep.WaitingForResults ->
            error("Expected a native observation")
    }

    fun ok(value: JsonObject): JsonObject = buildJsonObject {
        put("status", "ok")
        put("value", value)
    }

    fun failure(message: String, errorType: String? = null): JsonObject = buildJsonObject {
        put("status", "error")
        put("message", message)
        if (errorType != null) put("errorType", errorType)
    }

    fun number(value: JsonObject, key: String, fallback: Long): Long =
        JsonScalars.str(value, key)?.toLongOrNull() ?: fallback
}
