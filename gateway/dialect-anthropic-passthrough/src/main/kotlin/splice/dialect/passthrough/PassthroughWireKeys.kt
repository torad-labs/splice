// NEW: the cross-file wire vocabulary — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). A vocabulary read by six collaborators belongs to none of them; every
// key below is read from at least two files after the split. Single-reader keys (TEMPERATURE,
// STRICT, DEPTH_CAP, ...) stay private beside the collaborator that alone reads them.
package splice.dialect.passthrough

internal const val MODEL = "model"
internal const val STREAM = "stream"
internal const val THINKING = "thinking"
internal const val OUTPUT_CONFIG = "output_config"
internal const val MESSAGES = "messages"
internal const val SYSTEM = "system"
internal const val TOOLS = "tools"
internal const val TOOL_CHOICE = "tool_choice"
internal const val CONTENT = "content"
internal const val CACHE_CONTROL = "cache_control"
internal const val TYPE_THINKING = "thinking"
internal const val TYPE_TOOL_RESULT = "tool_result"

// V4-32: the two call sites that rewrite a tool name — the tool declaration and a replayed
// tool_use block — must agree on both spellings or the rewrite is only half applied.
internal const val TYPE_TOOL_USE = "tool_use"
internal const val NAME = "name"
