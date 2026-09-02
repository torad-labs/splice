// NEW: Anthropic tool / media wire shapes, split from AnthropicRequest.kt so the
// request envelope is not billed for the tool catalogue (concentration, 2026-08-20).
// Top-level names stay — ToolDefinition / ToolChoice / MediaSource are already
// imported by that simple name across dialects.
package splice.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
