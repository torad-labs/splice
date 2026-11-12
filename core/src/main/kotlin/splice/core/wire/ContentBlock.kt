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
    public data class DocumentBlock(
        val source: MediaSource? = null,
        val title: String? = null,
        val context: String? = null,
        /** Source citation configuration stays available for destination admission. */
        val citations: JsonObject? = null,
        /** Anthropic's block-local cache breakpoint, not a destination content field. */
        @SerialName("cache_control") val cacheControl: JsonObject? = null,
    ) : ContentBlock()

    /** [signature] is what the client round-trips beside the text: a vendor's, or one splice minted
     *  ([splice.core.turn.SpliceSignatures]), which is how a dialect that sends thinking back tells splice's own wait
     *  notice from the model's reasoning. Null when the client sent none. */
    @Serializable
    public data class ThinkingBlock(val thinking: String = "", val signature: String? = null) : ContentBlock()

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

        /** The tool a `tool_reference` block says is now loaded. The name comes only from the block's own
         *  `tool_name`, and only when it is a plain tool-name token (letters, digits, `_`, `-`, 1 to
         *  [TOOL_NAME_MAX_LENGTH] of them), so a result cannot put a sentence of its own into the marker.
         *  Null for every other block, and for a reference whose name is missing or anything else. */
        public val loadedTool: String?
            get() = (raw[TOOL_NAME_FIELD] as? JsonPrimitive)
                ?.takeIf { type == TOOL_REFERENCE_TYPE && it.isString }?.content
                ?.takeIf { name -> name.length in 1..TOOL_NAME_MAX_LENGTH && name.all { it in TOOL_NAME_CHARS } }

        /** What a dialect that cannot carry this block says in its place, so the model knows content was omitted.
         *  A `tool_reference` is the exception when [toolsCallable] says the dialect forwards the client's tool list
         *  as the model's own: the client meant "this tool's schema is now loaded", and the sentence says that, so
         *  the model does not read a loaded tool as a failed load. Any other block, a reference with no usable
         *  name, and a dialect that cannot promise the tool is in the list keep the omission text. */
        public fun omissionMarker(providerTag: String, toolsCallable: Boolean = false): String =
            loadedTool?.takeIf { toolsCallable }?.let { "[tool loaded: $it. It can be called directly.]" }
                ?: "[${type.ifEmpty { "unknown" }} block omitted by $providerTag proxy: unsupported on this backend]"
    }
}

private const val TOOL_REFERENCE_TYPE = "tool_reference"
private const val TOOL_NAME_FIELD = "tool_name"

// why: Anthropic's Messages API accepts tool names of 1 to 64 letters, digits, `_` and `-`
// (`^[a-zA-Z0-9_-]{1,64}$`), so a longer name is not one the client could have loaded.
private const val TOOL_NAME_MAX_LENGTH = 64
private const val TOOL_NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
