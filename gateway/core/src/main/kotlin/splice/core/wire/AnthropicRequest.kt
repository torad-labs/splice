// PORT-OF: the Anthropic Messages request shapes READ by server/src/codex/translate-request.mjs
// + grok/translate-request.mjs @ pre-public-port-baseline — invariants: content is string OR block list; system is
// string OR text-block list; tool_result content is string OR block list; tool input/input_schema
// stay opaque JsonObject; UNKNOWN block types must decode (never throw) so new client block kinds
// degrade gracefully; thinking.type disabled/disabled_thinking disables reasoning.
// The serializers named in the @Serializable(with = ...) annotations below are declared in
// AnthropicWireCodecs.kt — same package, so nothing here imports them.
// Content-block subtypes live nested in ContentBlock.kt; the typealiases below keep
// `import splice.core.wire.TextBlock` working. ToolDefinition/ToolChoice/MediaSource live
// in AnthropicTools.kt.
package splice.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
public data class ThinkingConfig(
    val type: String = "",
    @SerialName("budget_tokens") val budgetTokens: Long? = null,
) {
    public val disabled: Boolean get() = type == "disabled" || type == "disabled_thinking"
}

public typealias TextBlock = ContentBlock.TextBlock
public typealias ImageBlock = ContentBlock.ImageBlock
public typealias DocumentBlock = ContentBlock.DocumentBlock
public typealias ThinkingBlock = ContentBlock.ThinkingBlock
public typealias RedactedThinkingBlock = ContentBlock.RedactedThinkingBlock
public typealias ToolUseBlock = ContentBlock.ToolUseBlock
public typealias ToolResultBlock = ContentBlock.ToolResultBlock
public typealias UnknownBlock = ContentBlock.UnknownBlock
