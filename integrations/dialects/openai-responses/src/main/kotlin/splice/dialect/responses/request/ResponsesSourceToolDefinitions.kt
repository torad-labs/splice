package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.JsonScalars
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock

/** Source declarations become callable only at an initial, historical-use, or explicit-addition boundary. */
internal object ResponsesSourceToolDefinitions {
    fun offered(body: AnthropicRequest): Sequence<ToolDefinition> = sequence {
        val known = body.tools.associateBy { it.name }.toMutableMap()
        yieldAll(body.tools.filterNot { it.deferLoading })
        for (message in body.messages) {
            for (block in message.content) {
                atBoundary(message.role, block, known)?.let { yield(it) }
                if (block is ToolResultBlock) {
                    block.content.asSequence()
                        .filterIsInstance<UnknownBlock>()
                        .mapNotNull { it.loadedTool }
                        .mapNotNull(known::get)
                        .forEach { tool -> yield(tool) }
                }
            }
        }
    }

    private fun atBoundary(
        role: String,
        block: ContentBlock,
        known: MutableMap<String, ToolDefinition>,
    ): ToolDefinition? = when {
        block is ToolUseBlock -> known[block.name]
        role == ResponsesContextMessage.CLIENT_ROLE &&
            block is UnknownBlock && block.type == SOURCE_TOOL_ADDITION ->
            fromAddition(block, known)?.also { known[it.name] = it }
        else -> null
    }

    fun fromAddition(block: UnknownBlock, known: Map<String, ToolDefinition>): ToolDefinition? {
        val wrapper = block.raw[SOURCE_TOOL_FIELD_TOOL] as? JsonObject ?: return null
        return when (JsonScalars.str(wrapper, SOURCE_TOOL_FIELD_TYPE)) {
            SOURCE_TOOL_REFERENCE -> JsonScalars.str(wrapper, SOURCE_TOOL_FIELD_NAME)?.let(known::get)
            SOURCE_TOOL_DEFINITION -> inlineDefinition(wrapper[SOURCE_TOOL_FIELD_DEFINITION] as? JsonObject)
            else -> null
        }
    }

    private fun inlineDefinition(source: JsonObject?): ToolDefinition? {
        val definition = source ?: return null
        val name = JsonScalars.str(definition, SOURCE_TOOL_FIELD_NAME) ?: return null
        return ToolDefinition(
            name = name,
            description = JsonScalars.str(definition, SOURCE_TOOL_FIELD_DESCRIPTION),
            inputSchema = definition[SOURCE_TOOL_FIELD_INPUT_SCHEMA] as? JsonObject,
            strict = (definition[SOURCE_TOOL_FIELD_STRICT] as? JsonPrimitive)?.booleanOrNull,
            deferLoading = (definition[SOURCE_TOOL_FIELD_DEFER_LOADING] as? JsonPrimitive)?.booleanOrNull == true,
            type = JsonScalars.str(definition, SOURCE_TOOL_FIELD_TYPE),
            allowedCallers = (definition[SOURCE_TOOL_FIELD_ALLOWED_CALLERS] as? JsonArray)
                ?.mapNotNull { JsonScalars.str(it) },
            inputExamples = definition[SOURCE_TOOL_FIELD_INPUT_EXAMPLES] as? JsonArray,
            eagerInputStreaming = (definition[SOURCE_TOOL_FIELD_EAGER_INPUT_STREAMING] as? JsonPrimitive)
                ?.booleanOrNull,
            cacheControl = definition[SOURCE_TOOL_FIELD_CACHE_CONTROL] as? JsonObject,
        )
    }
}
