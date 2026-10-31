// NEW: Anthropic tool / media wire shapes, split from AnthropicRequest.kt so the
// request envelope is not billed for the tool catalogue (concentration, 2026-08-20).
// Top-level names stay — ToolDefinition / ToolChoice / MediaSource are already
// imported by that simple name across dialects.
package splice.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
public data class MediaSource(
    val type: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
    /** Public document sources carry either a literal string or ordered text/image blocks. */
    val content: JsonElement? = null,
)

@Serializable
public data class ToolDefinition(
    val name: String = "",
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject? = null,
    val strict: Boolean? = null,
    /** Held out of the initial Anthropic tool surface until a mid-conversation tool_addition references it. */
    @SerialName("defer_loading") val deferLoading: Boolean = false,
    /** Absent or custom for a user-defined function; other values name Anthropic-native tool protocols. */
    @SerialName("type") val type: String? = null,
    /** Restricts whether the model may call this tool directly or only through Anthropic code execution. */
    @SerialName("allowed_callers") val allowedCallers: List<String>? = null,
    /** Examples that steer Anthropic's tool-call generation. */
    @SerialName("input_examples") val inputExamples: JsonArray? = null,
    /** Anthropic's fine-grained tool-input streaming switch. */
    @SerialName("eager_input_streaming") val eagerInputStreaming: Boolean? = null,
    /** Anthropic's tool-definition prompt-cache breakpoint. */
    @SerialName("cache_control") val cacheControl: JsonObject? = null,
)

@Serializable
public data class ToolChoice(
    val type: String = "auto",
    val name: String? = null,
    @SerialName("disable_parallel_tool_use") val disableParallelToolUse: Boolean? = null,
)
