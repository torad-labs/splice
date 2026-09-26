// NEW: Anthropic content-block sealed tree, nested so the catalogue bills as one type
// (concentration, 2026-08-20). Split from AnthropicRequest.kt; same-package typealiases
// there keep `import splice.core.wire.TextBlock` working for in-tree consumers.
package splice.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable(with = ContentBlockSerializer::class)
public sealed class ContentBlock {
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
}
