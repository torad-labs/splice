package splice.app.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep

internal object CodeModeParityPayload {
    fun outcome(cellId: String, step: CodeModeStep): JsonObject = buildJsonObject {
        val kind = kind(step)
        put(
            kind,
            buildJsonObject {
                put("cell_id", cellId)
                put("content_items", JsonArray(content(step)))
                put("code_mode_host_duration_ns", 0)
                if (kind == "Result") {
                    put(
                        "error_text",
                        (step as? CodeModeStep.Completed)?.error?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            },
        )
    }

    private fun kind(step: CodeModeStep): String = when (step) {
        is CodeModeStep.Yielded -> "Yielded"
        is CodeModeStep.Completed -> if (step.terminated) "Terminated" else "Result"
        else -> error("Expected a native observation")
    }

    private fun content(step: CodeModeStep): List<JsonObject> = when (step) {
        is CodeModeStep.Yielded -> step.content
        is CodeModeStep.Completed -> step.content
        else -> error("Expected a native observation")
    }

    fun ok(value: JsonObject): JsonObject = buildJsonObject {
        put("status", "ok")
        put("value", value)
    }

    fun failure(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("message", message)
    }

    fun number(value: JsonObject, key: String, fallback: Long): Long =
        JsonScalars.str(value, key)?.toLongOrNull() ?: fallback
}
