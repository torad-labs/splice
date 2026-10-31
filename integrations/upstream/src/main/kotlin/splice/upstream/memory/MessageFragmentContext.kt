package splice.upstream.memory

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolDefinition

/** Request identity without any message/history graph or transport headers.
 *  Builder namespaces isolate heads, dialects and immutable quirks. Later prompt layers/code-mode
 *  shaping still run on every request; these fragments precede and do not consume those layers.
 */
public object MessageFragmentContext {
    public fun of(body: AnthropicRequest, raw: JsonObject): JsonObject = buildJsonObject {
        put("raw", JsonObject(raw - "messages"))
        put("model", body.model)
        body.system?.let { put("system", it) }
        put("tools", Json.encodeToJsonElement(ListSerializer(ToolDefinition.serializer()), body.tools))
        body.toolChoice?.let { put("tool_choice", Json.encodeToJsonElement(it)) }
        body.thinking?.let { put("thinking", Json.encodeToJsonElement(it)) }
        body.maxTokens?.let { put("max_tokens", it) }
        put("stream", body.stream)
    }
}
