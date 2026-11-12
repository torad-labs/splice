package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions

/** Raw protocol augmentation and typed ToolSpec collection keep their distinct schema boundaries. */
internal object CodeModeProbeDefinitions {
    fun evaluate(input: JsonObject, operation: String): JsonObject = when (operation) {
        "describe" -> buildJsonObject {
            put(
                "description",
                CodeModeManual.description(
                    CodeModeProbeTools.read(input[PROBE_TOOLS]),
                    CodeModeProbeTools.flag(input, "deferred", false),
                    CodeModeProbeTools.options(input["options"] as? JsonObject),
                ),
            )
        }
        "collect" -> collected(input)
        "augment" -> augmented(input)
        else -> {
            val budget = CodeModeProbeTools.budget(input, "code_mode_input_schema_max_bytes")
            val tools = CodeModeProbeTools.typed(input[PROBE_TOOLS], budget)
            prepared(input, tools)
        }
    }

    private fun augmented(input: JsonObject): JsonObject = buildJsonObject {
        val definitions = CodeModeProbeTools.loadable(input[PROBE_TOOLS]).map { tool ->
            tool.copy(description = CodeModeManual.augmented(tool), inputSchema = null, outputSchema = null)
        }
        put("prepared", JsonArray(definitions.map(CodeModeProbeTools::wire)))
    }

    private fun collected(input: JsonObject): JsonObject {
        val specs = (input["specs"] as? JsonArray).orEmpty()
        val budget = CodeModeProbeTools.budget(input, "code_mode_input_schema_max_bytes")
        return prepared(
            input,
            CodeModeToolDefinitions.collect(specs).map { CodeModeManual.withInputBudget(it, budget) },
        )
    }

    private fun prepared(
        input: JsonObject,
        definitions: List<CodeModeManual.NestedTool>,
    ): JsonObject = buildJsonObject {
        put(
            "prepared",
            JsonArray(
                CodeModeManual.prepare(
                    definitions,
                    CodeModeProbeTools.flag(input, "tool_description_first", false),
                ).map(CodeModeProbeTools::wire),
            ),
        )
    }
}

private const val PROBE_TOOLS = "tools"
