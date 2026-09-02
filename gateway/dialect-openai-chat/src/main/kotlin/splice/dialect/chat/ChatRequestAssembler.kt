// NEW: (HD-24) the closed-DTO request assembly lifted out of ChatRequestBuilder.kt. This code
// serializes the exact ChatRequest field set and then splices in the vendor-dynamic max_tokens
// key, which is precisely what ChatRequest.kt's own header says "ChatRequestBuilder" does —
// pairing the DTO field set with the code that assembles it puts the TIER-1 closed-DTO invariant
// in two adjacent files instead of two distant ones.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolChoiceMapping

/** The per-request knob pair threaded to [ChatRequestAssembler.chatRequestObject] (LongParameterList budget). */
internal data class ChatKnobs(val effort: String?, val cacheKey: String?)

internal class ChatRequestAssembler(private val quirks: ChatQuirks, private val wire: ChatWireMapper) {

    fun chatRequestObject(
        upstreamModel: String,
        messages: JsonArray,
        emitTools: Boolean,
        body: AnthropicRequest,
        knobs: ChatKnobs,
    ): JsonObject {
        val effort = knobs.effort
        val dto = ChatRequest(
            model = upstreamModel,
            messages = messages,
            stream = true,
            tools = if (emitTools) wire.toolsArray(body) else null,
            toolChoice = if (emitTools) ToolChoiceMapping.openAiToolChoice(body.toolChoice) else null,
            reasoningEffort = if (quirks.emitReasoningEffort) effort else null,
            reasoning = if (quirks.emitReasoningEffort && effort != null) {
                buildJsonObject { put("effort", effort) }
            } else {
                null
            },
            promptCacheKey = knobs.cacheKey,
            streamOptions = if (quirks.emitUsageInStream) {
                buildJsonObject { put("include_usage", true) }
            } else {
                null
            },
        )
        val fields = (chatRequestJson.encodeToJsonElement(ChatRequest.serializer(), dto) as JsonObject).toMutableMap()
        body.maxTokens?.takeIf { it > 0 }?.let { fields[quirks.maxTokensField] = JsonPrimitive(it) }
        return JsonObject(fields)
    }
}
