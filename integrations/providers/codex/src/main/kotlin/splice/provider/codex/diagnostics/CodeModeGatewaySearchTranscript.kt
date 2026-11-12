package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.ANTHROPIC_ASSISTANT_ROLE
import splice.upstream.codemode.CodeModeResult

/** One search probe conversation told twice: as Claude Code's messages, and as the Responses input the turn
 *  builder must carry for them. */
internal class CodeModeGatewaySearchTranscript(private val fixture: CodeModeGatewayProbeSupport) {
    fun client(callbacks: List<JsonObject>): List<JsonElement> = listOf(
        fixture.user("begin-${fixture.nonce}"),
    ) +
        callbacks.flatMap { callback ->
            listOf(
                buildJsonObject {
                    put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, ANTHROPIC_ASSISTANT_ROLE); put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT,
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                                        "tool_use",
                                    ); put(
                                        CALL_ID,
                                        callback.getValue(CALL_ID),
                                    ); put(
                                        CALL_NAME,
                                        callback.getValue(CALL_NAME),
                                    )
                                    put("input", callback.getValue(CALL_ARGUMENTS))
                                },
                            )
                        },
                    )
                },
                buildJsonObject {
                    put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, "user"); put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT,
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                                        "tool_result",
                                    ); put(
                                        "tool_use_id",
                                        callback.getValue(CALL_ID),
                                    ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_CONTENT, callback.getValue("result"))
                                    callback["is_error"]?.let { put("is_error", it) }
                                },
                            )
                        },
                    )
                },
            )
        }

    fun input(callbacks: List<JsonObject>, results: Map<String, CodeModeResult>): List<JsonElement> = listOf(
        fixture.user("begin-${fixture.nonce}"),
    ) +
        callbacks.flatMap { callback ->
            listOf(
                buildJsonObject {
                    put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                        "function_call",
                    ); put(
                        "call_id",
                        callback.getValue(CALL_ID),
                    ); put(
                        CALL_NAME,
                        callback.getValue(CALL_NAME),
                    )
                    put(
                        CALL_ARGUMENTS,
                        JsonWire.string(callback.getValue(CALL_ARGUMENTS)),
                    )
                },
                buildJsonObject {
                    put(
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                        "function_call_output",
                    ); put("call_id", callback.getValue(CALL_ID))
                    put(
                        "output",
                        results.getValue(
                            checkNotNull(JsonScalars.str(callback, CALL_ID)),
                        ).output,
                    )
                },
            )
        }
}
