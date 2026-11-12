package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.LogSink
import splice.provider.codex.CODE_MODE_WAIT_TOOL_NAME
import splice.upstream.codemode.CodeModeManual

internal object CodeModeWaitTool {
    fun declaration(
        messages: CodeModeCatalogMessages = CodeModeCatalogMessages(null),
        log: LogSink = LogSink {},
    ): JsonObject {
        val bundled = bundled()
        val description = messages.waitDescription ?: CodeModeManual.waitDescription()
        val parameterOverride = messages.waitParameters?.let(CodeModeCatalogParameters::parse)
        parameterOverride?.reason?.let { reason ->
            log(
                "[code-mode] Invalid catalog tool parameters for wait: $reason; using bundled parameters",
            )
        }
        val parameters = parameterOverride?.schema ?: bundled.getValue(WAIT_PARAMETERS)
        return JsonObject(
            bundled + (
                CODE_MODE_WAIT_TOOL_DESCRIPTION to kotlinx.serialization.json.JsonPrimitive(
                    description,
                )
                ) + (WAIT_PARAMETERS to parameters),
        )
    }

    private fun bundled(): JsonObject = buildJsonObject {
        put(CODE_MODE_WAIT_TOOL_TYPE, "function")
        put("name", CODE_MODE_WAIT_TOOL_NAME)
        put(
            CODE_MODE_WAIT_TOOL_DESCRIPTION,
            CodeModeManual.waitDescription(),
        )
        put("strict", false)
        putJsonObject(WAIT_PARAMETERS) {
            put(CODE_MODE_WAIT_TOOL_TYPE, "object")
            putJsonObject("properties") {
                putJsonObject("cell_id") {
                    put(CODE_MODE_WAIT_TOOL_TYPE, "string")
                    put(CODE_MODE_WAIT_TOOL_DESCRIPTION, "Identifier of the running exec cell.")
                }
                putJsonObject("max_tokens") {
                    put(CODE_MODE_WAIT_TOOL_TYPE, "number")
                    put(
                        CODE_MODE_WAIT_TOOL_DESCRIPTION,
                        "Output token budget for this wait call. Defaults to 10000 tokens.",
                    )
                }
                putJsonObject("terminate") {
                    put(CODE_MODE_WAIT_TOOL_TYPE, "boolean")
                    put(
                        CODE_MODE_WAIT_TOOL_DESCRIPTION,
                        "True stops the running exec cell; false or omitted waits for output.",
                    )
                }
                putJsonObject("yield_time_ms") {
                    put(CODE_MODE_WAIT_TOOL_TYPE, "number")
                    put(CODE_MODE_WAIT_TOOL_DESCRIPTION, "Wait before yielding more output. Defaults to 10000 ms.")
                }
            }
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("cell_id")) })
            put("additionalProperties", false)
        }
    }
}

private const val WAIT_PARAMETERS = "parameters"
private const val CODE_MODE_WAIT_TOOL_DESCRIPTION = "description"
private const val CODE_MODE_WAIT_TOOL_TYPE = "type"
