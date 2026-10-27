package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonObject

/** Endpoint-owned negotiation and warmup admission, independent of executor selection. */
public class ResponsesWebSocketPolicy(
    public val negotiationHeaders: Map<String, String> = emptyMap(),
    private val additionalToolsPrefillOnly: Boolean = false,
) {
    internal fun acceptsPrefill(request: JsonObject): Boolean =
        !additionalToolsPrefillOnly || ResponsesWsPrefill.hasAdditionalToolsPrefix(request)
}
