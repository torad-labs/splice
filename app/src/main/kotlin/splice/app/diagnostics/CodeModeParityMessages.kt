package splice.app.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.codemode.CodeModeStep

/** V1 envelopes only; observation semantics remain in the production runtime. */
internal object CodeModeParityMessages {
    fun hello(): JsonObject = buildJsonObject {
        put("type", "connection/ready")
        put("selectedVersion", 1)
        put("capabilities", JsonArray(emptyList()))
    }
    fun response(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("type", "operation/response"); put("id", id); put("result", result)
    }
    fun session(session: String, type: String): JsonObject = CodeModeParityPayload.ok(
        buildJsonObject {
            put("type", type); put("sessionId", session)
        },
    )
    fun started(cellId: String): JsonObject = CodeModeParityPayload.ok(
        buildJsonObject {
            put("type", "execution/started"); put("cellId", cellId)
        },
    )
    fun initial(id: JsonElement, cellId: String, step: CodeModeStep): JsonObject = buildJsonObject {
        put("type", "execute/initialResponse"); put("id", id)
        put("result", CodeModeParityPayload.ok(CodeModeParityPayload.outcome(cellId, step)))
    }
    fun waited(
        cellId: String,
        step: CodeModeStep,
        found: Boolean,
    ): JsonObject = CodeModeParityPayload.ok(
        buildJsonObject {
            put("type", "wait/completed")
            put(
                "outcome",
                buildJsonObject {
                    put(if (found) "LiveCell" else "MissingCell", CodeModeParityPayload.outcome(cellId, step))
                },
            )
        },
    )
    fun delegate(id: String, held: CodeModeParityCell, request: JsonObject): JsonObject = buildJsonObject {
        put("type", "delegate/request"); put("id", id); put("sessionId", held.session); put("request", request)
    }
    fun cancelled(id: String): JsonObject = buildJsonObject {
        put("type", "delegate/cancel"); put("id", id)
    }
}
