package splice.dialect.responses.request

/** Anthropic's source-tool wrapper and declaration fields, shared by every raw source-tool reader. */
internal const val SOURCE_TOOL_FIELD_TOOL = "tool"
internal const val SOURCE_TOOL_FIELD_NAME = "name"
internal const val SOURCE_TOOL_FIELD_DEFINITION = "definition"
internal const val SOURCE_TOOL_FIELD_TYPE = "type"
internal const val SOURCE_TOOL_FIELD_DESCRIPTION = "description"
internal const val SOURCE_TOOL_FIELD_INPUT_SCHEMA = "input_schema"
internal const val SOURCE_TOOL_FIELD_STRICT = "strict"
internal const val SOURCE_TOOL_FIELD_DEFER_LOADING = "defer_loading"
internal const val SOURCE_TOOL_FIELD_ALLOWED_CALLERS = "allowed_callers"
internal const val SOURCE_TOOL_FIELD_INPUT_EXAMPLES = "input_examples"
internal const val SOURCE_TOOL_FIELD_EAGER_INPUT_STREAMING = "eager_input_streaming"
internal const val SOURCE_TOOL_FIELD_CACHE_CONTROL = "cache_control"

internal const val SOURCE_TOOL_REFERENCE = "tool_reference"
internal const val SOURCE_TOOL_DEFINITION = "tool_definition"
internal const val SOURCE_TOOL_ADDITION = "tool_addition"
internal const val SOURCE_TOOL_REMOVAL = "tool_removal"
