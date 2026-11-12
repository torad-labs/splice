package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Endpoint-owned negotiation and warmup admission, independent of executor selection. */
public class ResponsesWebSocketPolicy(
    public val negotiationHeaders: Map<String, String> = emptyMap(),
    private val additionalToolsPrefillOnly: Boolean = false,
    /** Names of the custom tool calls the provider's own runtime executes (code mode's exec and wait). An ordinary
     *  turn never closes one of these for it: it stays open and the turn full-sends, as before. */
    private val runtimeCalls: Set<String> = emptySet(),
) {
    internal fun acceptsPrefill(request: JsonObject): Boolean =
        !additionalToolsPrefillOnly || ResponsesWsPrefill.hasAdditionalToolsPrefix(request)

    /** Whether [call] is one the provider's runtime runs itself. */
    internal fun runsItself(call: JsonObject): Boolean =
        JsonScalars.str(call[WS_FIELD_TYPE]) == CUSTOM_TOOL_CALL && JsonScalars.str(call["name"]) in runtimeCalls
}

private const val CUSTOM_TOOL_CALL = "custom_tool_call"
