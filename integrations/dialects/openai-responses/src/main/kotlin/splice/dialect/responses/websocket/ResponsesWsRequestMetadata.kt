// NEW: per-request WebSocket protocol metadata without changing the logical request or cache identity.
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnMeta
import splice.core.util.JsonScalars

/** The backend's first-write-wins routing token, scoped to one turn in codex-rs client.rs:620. */
public const val CODEX_TURN_STATE_HEADER: String = "x-codex-turn-state"

private const val LITE_HEADER = "x-openai-internal-codex-responses-lite"
private const val LITE_METADATA = "ws_request_header_x_openai_internal_codex_responses_lite"

internal class ResponsesWsRequestMetadata {
    /** codex-rs 14a477ea8 core/src/client.rs:822-834: the lite marker is the STRING "true". */
    fun forTurn(
        request: JsonObject,
        headers: Map<String, String>,
        captured: Map<String, String>,
    ): JsonObject? {
        val lite = headers.entries.any { it.key.equals(LITE_HEADER, ignoreCase = true) && it.value == "true" }
        val state = captured[CODEX_TURN_STATE_HEADER]
        if (!lite && state == null) return null
        return buildJsonObject {
            (request["client_metadata"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            if (lite) put(LITE_METADATA, "true")
            state?.let { put(CODEX_TURN_STATE_HEADER, it) }
        }
    }

    /** codex-rs 14a477ea8 codex-api/src/sse/responses.rs:210-217,280-287,323-327. */
    fun captureEvent(meta: TurnMeta, event: JsonObject) {
        if (JsonScalars.str(event["type"]) != "response.metadata") return
        val headers = event["headers"] as? JsonObject ?: return
        var value: JsonElement? = headers.entries
            .firstOrNull { it.key.equals(CODEX_TURN_STATE_HEADER, ignoreCase = true) }?.value
        while (value is JsonArray) value = value.firstOrNull()
        if (value is JsonPrimitive && value.isString) {
            meta.upstreamHeaders.capture(CODEX_TURN_STATE_HEADER, JsonScalars.str(value))
        }
    }
}
