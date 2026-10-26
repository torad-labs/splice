package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import splice.upstream.codemode.CodeModeToolOutputs

internal object CodeModeProbeOutputs {
    fun evaluate(input: JsonObject): JsonObject {
        val origin = if (JsonScalars.str(
                input,
                "origin",
            ) == "mcp"
        ) {
            CodeModeResultOrigin.MCP
        } else {
            CodeModeResultOrigin.GENERIC
        }
        val disposition = if (JsonScalars.str(input, "disposition") == "tool_output") {
            CodeModeResultDisposition.TOOL_OUTPUT
        } else {
            CodeModeResultDisposition.REJECT
        }
        val content = (input["content"] as? JsonArray)?.map { it as JsonObject }
        val result = CodeModeResult(
            "probe",
            content?.let(CodeModeToolOutputs::content) ?: JsonScalars.str(input, "output").orEmpty(),
            CodeModeProbeTools.flag(input, "is_error", false),
            input["payload"],
            disposition,
            origin,
        )
        return buildJsonObject {
            put("value", CodeModeToolOutputs.value(result))
            put("rejected", result.isError && result.disposition == CodeModeResultDisposition.REJECT)
        }
    }
}
