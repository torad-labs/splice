// PORT-OF: the Anthropic Messages request shapes READ by server/src/codex/translate-request.mjs
// + grok/translate-request.mjs @ pre-public-port-baseline — invariants: content is string OR block list; system is
// string OR text-block list; tool_result content is string OR block list; tool input/input_schema
// stay opaque JsonObject; UNKNOWN block types must decode (never throw) so new client block kinds
// degrade gracefully; thinking.type disabled/disabled_thinking disables reasoning.
// The serializers named in the @Serializable(with = ...) annotations below are declared in
// AnthropicWireCodecs.kt — same package, so nothing here imports them.
package splice.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
public data class AnthropicRequest(
    val model: String = "",
    val messages: List<AnthropicMessage> = emptyList(),
    @Serializable(with = SystemTextSerializer::class)
    val system: String? = null,
    val tools: List<ToolDefinition> = emptyList(),
    @SerialName("tool_choice") val toolChoice: ToolChoice? = null,
    val thinking: ThinkingConfig? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
    val stream: Boolean = false,
)

@Serializable
public data class AnthropicMessage(
    val role: String,
    @Serializable(with = ContentSerializer::class)
    val content: List<ContentBlock> = emptyList(),
)

@Serializable(with = ContentBlockSerializer::class)
public sealed class ContentBlock

@Serializable
public data class TextBlock(val text: String = "") : ContentBlock()

@Serializable
public data class ImageBlock(val source: MediaSource? = null) : ContentBlock()

@Serializable
public data class DocumentBlock(val source: MediaSource? = null) : ContentBlock()

@Serializable
public data class ThinkingBlock(val thinking: String = "") : ContentBlock()

@Serializable
public data class RedactedThinkingBlock(val data: String = "") : ContentBlock()

@Serializable
public data class ToolUseBlock(
    val id: String = "",
    val name: String = "",
    val input: JsonObject = JsonObject(emptyMap()),
) : ContentBlock()

@Serializable
public data class ToolResultBlock(
    @SerialName("tool_use_id") val toolUseId: String = "",
    @Serializable(with = ContentSerializer::class)
    val content: List<ContentBlock> = emptyList(),
    /** Anthropic's structured failure verdict. `null` means the client said nothing — NOT `false`;
     *  consumers fall back to text heuristics only in that case. */
    @SerialName("is_error") val isError: Boolean? = null,
) : ContentBlock()

/** Unknown block kinds decode losslessly instead of throwing (forward compatibility). */
@Serializable(with = UnknownBlockSerializer::class)
public data class UnknownBlock(val raw: JsonObject) : ContentBlock() {
    public val type: String get() = raw["type"]?.let { (it as? JsonPrimitive)?.content } ?: ""
}

@Serializable
public data class MediaSource(
    val type: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
)

@Serializable
public data class ToolDefinition(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject? = null,
    val strict: Boolean? = null,
)

@Serializable
public data class ToolChoice(
    val type: String = "auto",
    val name: String? = null,
    @SerialName("disable_parallel_tool_use") val disableParallelToolUse: Boolean? = null,
)

@Serializable
public data class ThinkingConfig(
    val type: String = "",
    @SerialName("budget_tokens") val budgetTokens: Long? = null,
) {
    public val disabled: Boolean get() = type == "disabled" || type == "disabled_thinking"
}
